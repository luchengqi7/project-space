package org.matsim.project.drtSchoolTransportStudy.jsprit.listener;

import com.graphhopper.jsprit.core.algorithm.listener.IterationStartsListener;
import com.graphhopper.jsprit.core.problem.VehicleRoutingProblem;
import com.graphhopper.jsprit.core.problem.solution.VehicleRoutingProblemSolution;
import com.graphhopper.jsprit.core.util.Solutions;

import java.util.Collection;

public class IterationScoreInfo implements IterationStartsListener {

    @Override
    public void informIterationStarts(int i, VehicleRoutingProblem problem, Collection<VehicleRoutingProblemSolution> solutions) {
        // Assume there is only 1 solution in the collection, which is the solution of the current iteration.
        if (i <= 1) {
            double costs = Solutions.bestOf(solutions).getCost();
            int fleetSize = Solutions.bestOf(solutions).getRoutes().size();
            System.out.println("Before iteration " + i);
            System.out.println("total costs = " + costs);
            System.out.println("vehicles used = " + fleetSize);
        }
    }
}
