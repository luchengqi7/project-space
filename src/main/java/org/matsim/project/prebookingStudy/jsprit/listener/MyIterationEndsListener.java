package org.matsim.project.prebookingStudy.jsprit.listener;

import com.graphhopper.jsprit.core.algorithm.listener.IterationEndsListener;
import com.graphhopper.jsprit.core.problem.VehicleRoutingProblem;
import com.graphhopper.jsprit.core.problem.solution.VehicleRoutingProblemSolution;
import com.graphhopper.jsprit.core.util.Solutions;
import org.matsim.project.prebookingStudy.jsprit.utils.StatisticCollectorForIterationEndsListener;

import java.util.Collection;

public class MyIterationEndsListener implements IterationEndsListener {

    @Override
    public void informIterationEnds(int i, VehicleRoutingProblem problem, Collection<VehicleRoutingProblemSolution> solutions) {
        double costs = Solutions.bestOf(solutions).getCost();
        int fleetSize = Solutions.bestOf(solutions).getRoutes().size();

        StatisticCollectorForIterationEndsListener.getCostsMap().put(i, costs);
        StatisticCollectorForIterationEndsListener.getFleetSizeMap().put(i, fleetSize);
    }
}
