package org.matsim.project.drtOperationStudy.rollingHorizon;

import com.graphhopper.jsprit.core.problem.VehicleRoutingProblem;
import com.graphhopper.jsprit.core.problem.job.Job;
import com.graphhopper.jsprit.core.problem.solution.SolutionCostCalculator;
import com.graphhopper.jsprit.core.problem.solution.VehicleRoutingProblemSolution;
import com.graphhopper.jsprit.core.problem.solution.route.VehicleRoute;
import com.graphhopper.jsprit.core.problem.solution.route.activity.TourActivity;

public class RollingHorizonObjectiveFunctionWithDiscount implements SolutionCostCalculator {
    private final VehicleRoutingProblem vrp;
    private final double interval;
    private final double horizon;
    private final double now;

    RollingHorizonObjectiveFunctionWithDiscount(VehicleRoutingProblem vrp, double horizon, double interval, double now) {
        this.vrp = vrp;
        this.interval = interval;
        this.horizon = horizon;
        this.now = now;
    }

    @Override
    public double getCosts(VehicleRoutingProblemSolution solution) {
        double costs = 0;
        for (VehicleRoute route : solution.getRoutes()) {
            costs += route.getVehicle().getType().getVehicleCostParams().fix;
            TourActivity prevAct = route.getStart();
            for (TourActivity act : route.getActivities()) {
                double t0 = (prevAct.getEndTime() - now) / horizon;
                double t1 = (act.getArrTime() - now) / horizon;
                double originalCost = vrp.getTransportCosts().getTransportCost(prevAct.getLocation(), act.getLocation(), prevAct.getEndTime(), route.getDriver(), route.getVehicle());
                double discountFactor = -2 * Math.exp(-0.5 * t1) + 2 * Math.exp(-0.5 * t0);

                costs += discountFactor * originalCost;
                costs += Math.exp(-0.5 * t1) * vrp.getActivityCosts().getActivityCost(act, act.getArrTime(), route.getDriver(), route.getVehicle());
                prevAct = act;
            }
        }

        for (Job j : solution.getUnassignedJobs()) {
            costs += PDPTWSolverJsprit.REJECTION_COST * (11 - j.getPriority());
        }
        return costs;
    }

}
