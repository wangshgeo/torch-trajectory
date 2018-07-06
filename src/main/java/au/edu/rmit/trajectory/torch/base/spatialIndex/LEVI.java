package au.edu.rmit.trajectory.torch.base.spatialIndex;

import au.edu.rmit.trajectory.torch.base.Torch;
import au.edu.rmit.trajectory.torch.base.WindowQueryIndex;
import au.edu.rmit.trajectory.torch.base.TopKQueryIndex;
import au.edu.rmit.trajectory.torch.base.helper.GeoUtil;
import au.edu.rmit.trajectory.torch.base.invertedIndex.VertexInvertedIndex;
import au.edu.rmit.trajectory.torch.base.model.TrajEntry;
import au.edu.rmit.trajectory.torch.base.model.Trajectory;
import au.edu.rmit.trajectory.torch.mapMatching.model.TowerVertex;
import au.edu.rmit.trajectory.torch.queryEngine.model.LightEdge;
import au.edu.rmit.trajectory.torch.queryEngine.model.SearchWindow;
import au.edu.rmit.trajectory.torch.queryEngine.similarity.SimilarityFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

import static au.edu.rmit.trajectory.torch.queryEngine.similarity.SimilarityFunction.*;

/**
 * ﻿LEVI stands for Lightweight edge & vertex vertexInvertedIndex.<p>
 * LEVI basically has two parts: grid vertexInvertedIndex and inverted vertexInvertedIndex on vertex.
 * This two level indexes structure supports range query as well as top k query over vertex.
 */
public class LEVI implements WindowQueryIndex, TopKQueryIndex {

    private static Logger logger = LoggerFactory.getLogger(LEVI.class);
    private VertexInvertedIndex vertexInvertedIndex;
    private VertexGridIndex gridIndex;
    private Map<String, Trajectory<TowerVertex>> trajectoryMapMemory;

    private MeasureType measureType;
    private SimilarityFunction<TrajEntry> similarityFunction = SimilarityFunction.DEFAULT;
    private Map<String, String[]> trajectoryPool;
    private Map<Integer, TowerVertex> idVertexLookup;
    
    public LEVI(VertexInvertedIndex vertexInvertedIndex, VertexGridIndex gridIndex,
                MeasureType measureType, Map<String, String[]> trajectoryPool, Map<Integer, TowerVertex> idVertexLookup){

        this.vertexInvertedIndex = vertexInvertedIndex;
        this.gridIndex = gridIndex;
        this.measureType = measureType;
        this.trajectoryPool = trajectoryPool;
        this.idVertexLookup = idVertexLookup;
    }

    @Override
    public boolean build(String Null) {
        
        if (!vertexInvertedIndex.loaded) vertexInvertedIndex.build(Torch.URI.VERTEX_INVERTED_INDEX);
        if (!gridIndex.loaded) gridIndex.build(Torch.URI.GRID_INDEX);
        
        return vertexInvertedIndex.loaded && gridIndex.loaded;
    }
    
    @Override
    public boolean useEdge() {
        return false;
    }


    //todo
    @Override
    public List<String> findInRange(SearchWindow window) {

        Collection<Integer> points = gridIndex.pointsInWindow(window);
        Set<String> ret = new HashSet<>();
        logger.debug("number of points in window: {}", points.size());

        for (Integer pointId : points)
            ret.addAll(vertexInvertedIndex.getKeys(pointId));
        logger.debug("number of trajectories in window: {}", ret.size());
        return new ArrayList<>(ret);
    }

    @Override
    public <T extends TrajEntry> List<String> findTopK(int k, List<T> pointQuery,  List<LightEdge> edgeQuery){

        PriorityQueue<Pair> topKHeap = new PriorityQueue<>((p1, p2)->Double.compare(p2.score,p1.score));
        double bestKthSoFar = Double.NaN, overallUnseenUpperBound = 0;
        double[] unseenUpperBounds = new double[pointQuery.size()];
        int round = 0;
        Set<String> visitTrajectorySet = new HashSet<>();


        int check = 0;
        while (check == 0) {

            overallUnseenUpperBound = 0;
            //each query point match with the nearest point of a trajectory,
            // and the lower bound is the maximun distance between a query and existing points of a trajectory
            Map<String, Double> trajBound = new HashMap<>();
            Map<String, Map<TrajEntry, Double>> trajLowerBoundForDTW = new HashMap<>();

            //findMoreVertices candiates incrementally and calculate their lower bound
            for (int i = 0; i < pointQuery.size() - 1; i++) {

                TrajEntry queryPoint = pointQuery.get(i);
                double upperBound = gridIndex.findBound(queryPoint, round);
                unseenUpperBounds[i] = upperBound;
                findMoreVertices(queryPoint, (List<TrajEntry>)pointQuery, round, measureType, trajBound, trajLowerBoundForDTW, unseenUpperBounds);

                switch (measureType) {
                    case DTW:
                        overallUnseenUpperBound += upperBound;
                        break;
                    case Hausdorff:
                        if (overallUnseenUpperBound > upperBound) overallUnseenUpperBound = upperBound;
                        break;
                    case Frechet:
                        if (overallUnseenUpperBound > upperBound) overallUnseenUpperBound = upperBound;
                        break;
                }
            }

            //rank trajectories by their upper bound
            PriorityQueue<Map.Entry<String, Double>> rankedCandidates = new PriorityQueue<>((e1,e2) -> Double.compare(e2.getValue(),e1.getValue()));

            for (Map.Entry<String, Double> entry : trajBound.entrySet()) {
                if (!visitTrajectorySet.contains(entry.getKey()))
                    rankedCandidates.add(entry);
            }
            //mark visited trajectories
            visitTrajectorySet.addAll(trajBound.keySet());
            logger.debug("total number of candidate trajectories in {}th round: {}", round, rankedCandidates.size());
            logger.debug("candidates");

            //calculate exact distance for each candidate
            int j = 0;
            while (!rankedCandidates.isEmpty()) {
                if (++j % 1000 == 0 && (measureType == MeasureType.Frechet || measureType == MeasureType.Hausdorff))
                    logger.info("has processed trajectories: {}, current kth realDist: {}, bound: {}", j, bestKthSoFar, overallUnseenUpperBound );

                Map.Entry<String, Double> entry1 = rankedCandidates.poll();
                String curTrajId = entry1.getKey();
                double curUpperBound = entry1.getValue();

                String[] trajectory = trajectoryPool.get(curTrajId);
                Trajectory<TrajEntry> t = new Trajectory<>();
                for (int i = 1; i < trajectory.length; i++) {
                    t.add(idVertexLookup.get(Integer.valueOf(trajectory[i])));
                }

                double realDist = 0;
                switch (measureType) {
                    case DTW:
                        realDist = similarityFunction.fastDynamicTimeWarping(t, (List<TrajEntry>)pointQuery, 10, bestKthSoFar);
                        break;
                    case Hausdorff:
                        realDist = similarityFunction.Hausdorff(t, (List<TrajEntry>)pointQuery);
                        break;
                    case Frechet:
                        realDist = similarityFunction.Frechet(t, (List<TrajEntry>)pointQuery);
                        break;
                }
                double score = -realDist;
                Pair pair = new Pair(curTrajId, score);
                if (topKHeap.size() < k)
                    topKHeap.offer(pair);
                else{
                    bestKthSoFar = topKHeap.peek().score;

                    if (bestKthSoFar > overallUnseenUpperBound)
                        check = 1;

                    if (bestKthSoFar > curUpperBound)
                        break;

                    if (bestKthSoFar < pair.score) {
                        topKHeap.poll();
                        topKHeap.offer(pair);
                    }
                }
            }

            bestKthSoFar = topKHeap.peek().score;
            logger.info("round: {}, kth score: {}, unseen bound: {}", round, bestKthSoFar, overallUnseenUpperBound);

            if (round == 5) {
                logger.error("round = 5");
                break;
            }
            ++round;
        }


        List<String> resIDList = new ArrayList<>();
        while (!topKHeap.isEmpty()) {
            resIDList.add(topKHeap.poll().trajectoryID);
        }

        return resIDList;
    }

    private void findMoreVertices(TrajEntry queryVertex, List<TrajEntry> pointQuery, int round, MeasureType measureType, Map<String, Double> existingTrajBound, Map<String, Map<TrajEntry, Double>> existingTrajIDLowerBoundForDTW, double[] unseenbounds) {

        //findMoreVertices the nearest pair between a trajectory and query queryVertex
        //trajectory hash, queryVertex hash vertices
        Set<Integer> vertices = new HashSet<>();
        gridIndex.incrementallyFind(queryVertex, round, vertices);
        for (Integer vertexId : vertices){
            Double score = - GeoUtil.distance(idVertexLookup.get(vertexId), queryVertex);
            List<String> l= vertexInvertedIndex.getKeys(vertexId);
            for (String trajId : l){

                if (measureType == MeasureType.DTW) {
                    Map<TrajEntry, Double> map = existingTrajIDLowerBoundForDTW.get(trajId);
                    if (map != null) {
                        if (!map.containsKey(queryVertex)||
                                score > map.get(queryVertex))
                            map.put(queryVertex, score);
                    } else {
                        map = existingTrajIDLowerBoundForDTW.computeIfAbsent(trajId, key -> new HashMap<>());
                        map.put(queryVertex, score);
                    }

                    double sum = 0.;
                    TrajEntry curQpoint;
                    for (int i = 0; i < pointQuery.size(); i++){
                        curQpoint = pointQuery.get(i);
                        if (map.keySet().contains(curQpoint))
                            sum += map.get(curQpoint);
                        else
                            sum += unseenbounds[i];
                    }

                    existingTrajBound.put(trajId, sum);
                } else{
                    if (existingTrajBound.get(trajId) == null){
                        existingTrajBound.put(trajId, score);
                    }else {
                        double pre = existingTrajBound.get(trajId);
                        existingTrajBound.put(trajId, Math.max(score, pre));
                    }
                }
            }
        }
    }

    static class Pair {
        final String trajectoryID;
        final double score;

        Pair(String trajectoryID, double score) {
            this.trajectoryID = trajectoryID;
            this.score = score;
        }
    }
}
