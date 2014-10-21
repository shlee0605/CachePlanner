/**
 * 
 */
package edu.duke.cacheplanner.algorithm.singleds;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import scpsolver.lpsolver.SolverFactory;
import scpsolver.problems.LPSolution;
import scpsolver.problems.LPWizard;
import scpsolver.problems.LPWizardConstraint;
import edu.duke.cacheplanner.algorithm.singleds.allocation.Allocation;
import edu.duke.cacheplanner.algorithm.singleds.allocation.AllocationDistribution;
import edu.duke.cacheplanner.algorithm.singleds.allocation.MergedAllocationDistribution;
import edu.duke.cacheplanner.data.Dataset;
import edu.duke.cacheplanner.query.SingleDatasetQuery;

/**
 * Heuristic algorithm to find an allocation that is max-min fair in terms of 
 * utilities to tenants
 * @author mayuresh
 *
 */
public class MMFBatchAnalyzer extends AbstractSingleDSBatchAnalyzer {

	AllocationDistribution Q;

	public MMFBatchAnalyzer(List<Dataset> datasets) {
		super(datasets);
	}

	/* (non-Javadoc)
	 * @see edu.duke.cacheplanner.algorithm.singleds.SingleDSBatchAnalyzer#analyzeBatch(java.util.List, java.util.List, double)
	 */
	@Override
	public List<Dataset> analyzeBatch(List<SingleDatasetQuery> queries,
			List<Dataset> cachedDatasets, double cacheSize) {

		initDataStructures(queries, cachedDatasets);

		buildUStars(cacheSize);

		Q = new MergedAllocationDistribution(
				generateQ(cacheSize));

		solveRecursively();

		//Randomly select a specific allocation from Q distribution
		Allocation output = new Allocation();
		output = Q.getRandomAllocation();

		return getCacheAllocation(queries, output);

	}

	private void solveRecursively() {
		int level = 0;
		Map<Integer, Double> maxValuePerLevel = new HashMap<Integer, Double>();
		Map<Integer, List<Integer>> saturatedUsersPerLevel = 
				new HashMap<Integer, List<Integer>>();
		while(true) {
			// LP for current level
			double maxValue = solveLP(level, maxValuePerLevel, 
					saturatedUsersPerLevel, true);
			maxValuePerLevel.put(level, maxValue);
			// find users who have got best possible utility
			List<Integer> newSaturated = getSaturatedUsers(level, 
					maxValuePerLevel, saturatedUsersPerLevel);
			saturatedUsersPerLevel.put(level, newSaturated);
			// breaking the loop
			if(flattenMap(saturatedUsersPerLevel).size() == N) {
				return;
			}
			// continue to next level
			level++;
		}
	}

	private List<Integer> getSaturatedUsers(int level,
			Map<Integer, Double> maxValuePerLevel,
			Map<Integer, List<Integer>> saturatedUsersPerLevel) {
		// users not saturated so far
		List<Integer> saturatedUsers = flattenMap(saturatedUsersPerLevel);
		List<Integer> unsaturatedUsers = new ArrayList<Integer>();
		for(int i=0; i<N; i++) {
			if(!saturatedUsers.contains(i)) {
				unsaturatedUsers.add(i);
			}
		}

		List<Integer> result = new ArrayList<Integer>();
		for(Integer user: unsaturatedUsers) {
			Map<Integer, List<Integer>> satUsersPerLevel = 
					new HashMap<Integer, List<Integer>>();
			satUsersPerLevel.putAll(saturatedUsersPerLevel);
			// all but the current user are saturated
			List<Integer> newSaturated = new ArrayList<Integer>();
			newSaturated.addAll(unsaturatedUsers);
			newSaturated.remove(user);
			satUsersPerLevel.put(level, newSaturated);
			// solve LP to see if the user can get more value
			double improvedValue = solveLP(level+1, maxValuePerLevel, 
					satUsersPerLevel, false);
			if(improvedValue == maxValuePerLevel.get(level)) {
				result.add(user);
			}
		}
		return result;
	}

	private double solveLP(int level, Map<Integer, Double> maxValuePerLevel, 
			Map<Integer, List<Integer>> saturatedUsersPerLevel, boolean updateQ) {
		List<Integer> saturatedUsers = flattenMap(saturatedUsersPerLevel);
		if(saturatedUsers.size() == N) {
			return -1;
		}
		LPWizard lpw = new LPWizard();
		lpw.plus("M" + level, "1.0");	//maximize M_level

		//unsaturated user constraints
		for(int i=0; i<N; i++) {
			if(!saturatedUsers.contains(i)) {
				LPWizardConstraint constraint = lpw.addConstraint(
						"unsat" + i, 0, "<=");
				addToUtilConstraint(constraint, i);
				constraint.plus("M" + level, -u_star[i]);
			}
		}

		//saturated user constraints
		for(Integer key: maxValuePerLevel.keySet()) {
			List<Integer> users = saturatedUsersPerLevel.get(key);
			for(Integer user: users) {
				LPWizardConstraint constraint = lpw.addConstraint("sat" + user, 
						maxValuePerLevel.get(key) * u_star[user], "<="); 
				addToUtilConstraint(constraint, user);
			}
		}

		//norm constraint
		LPWizardConstraint constraint = lpw.addConstraint("norm", 1, "=");
		addToNormConstraint(constraint);

		//get max result
		LPSolution solution = lpw.solve(SolverFactory.newDefault());
		double value = solution.getDouble("M" + level);

		//update x
		if(updateQ) {
			int j=0;
			System.out.println("***Allocation at level " + level + " = " + value);
			for(Allocation S: Q.getAllocations()) {
				S.setCacheProb(solution.getDouble("x" + j));
				System.out.println(j + ": " + S.getCacheProb());
				j++;
			}
		}

		return value;

	}

	private void addToNormConstraint(LPWizardConstraint constraint) {
		int j = 0;
		for(Allocation S: Q.getAllocations()) {
			constraint.plus("x" + j, 1.0);
			j++;
		}		
	}

	private void addToUtilConstraint(LPWizardConstraint constraint,	int user) {
		int j = 0;
		for(Allocation S: Q.getAllocations()) {
			constraint.plus("x" + j, S.getPrecomputed()[user]);
			j++;
		}
	}

	private List<Integer> flattenMap(Map<Integer, List<Integer>> mapToFlatten) {
		List<Integer> list = new ArrayList<Integer>();
		for (Integer key: mapToFlatten.keySet()) {
			list.addAll(mapToFlatten.get(key));
		}
		return list;
	}
}

