package org.matsim.project.drtOperationStudy.rollingHorizon;

import com.graphhopper.jsprit.core.problem.VehicleRoutingProblem;
import com.graphhopper.jsprit.core.problem.job.Job;
import com.graphhopper.jsprit.core.problem.solution.SolutionCostCalculator;
import com.graphhopper.jsprit.core.problem.solution.VehicleRoutingProblemSolution;
import com.graphhopper.jsprit.core.problem.solution.route.VehicleRoute;
import com.graphhopper.jsprit.core.problem.solution.route.activity.TourActivity;
import org.matsim.api.core.v01.Id;
import org.matsim.contrib.dvrp.fleet.DvrpVehicle;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.matsim.project.drtOperationStudy.rollingHorizon.PDPTWSolverJsprit.REJECTION_COST;

public class RollingHorizonObjectiveFunctionWithDiversionCosts implements SolutionCostCalculator {
    private final VehicleRoutingProblem vrp;
    private final List<Id<DvrpVehicle>> activeVehicles = new ArrayList<>();
    private final Map<Id<DvrpVehicle>, RollingHorizonDrtOptimizer.OnlineVehicleInfo> realTimeVehicleInfoMap;
    private final double now;

    RollingHorizonObjectiveFunctionWithDiversionCosts(VehicleRoutingProblem vrp, RollingHorizonDrtOptimizer.PreplannedSchedules previousSchedule,
                                                      Map<Id<DvrpVehicle>, RollingHorizonDrtOptimizer.OnlineVehicleInfo> realTimeVehicleInfoMap, double now) {
        this.vrp = vrp;
        this.realTimeVehicleInfoMap = realTimeVehicleInfoMap;
        this.now = now;
        if (previousSchedule != null) {
            for (Id<DvrpVehicle> vehId : previousSchedule.vehicleToPreplannedStops().keySet()) {
                if (!previousSchedule.vehicleToPreplannedStops().get(vehId).isEmpty()) {
                    activeVehicles.add(vehId);
                }
            }
        }
    }

    @Override
    public double getCosts(VehicleRoutingProblemSolution solution) {
        List<Id<DvrpVehicle>> newActiveVehicles = new ArrayList<>();
        double costs = 0;

        // adding driving costs in the solution
        for (VehicleRoute route : solution.getRoutes()) {
            newActiveVehicles.add(Id.create(route.getVehicle().getId(), DvrpVehicle.class));

            costs += route.getVehicle().getType().getVehicleCostParams().fix;
            TourActivity prevAct = route.getStart();
            for (TourActivity act : route.getActivities()) {
                costs += vrp.getTransportCosts().getTransportCost(prevAct.getLocation(), act.getLocation(), prevAct.getEndTime(), route.getDriver(), route.getVehicle());
                costs += vrp.getActivityCosts().getActivityCost(act, act.getArrTime(), route.getDriver(), route.getVehicle());
                prevAct = act;
            }
        }

        // adding penalty for rejections
        for (Job j : solution.getUnassignedJobs()) {
            if (j.getPriority() <= 1) {
                costs += REJECTION_COST * 10000;
            } else {
                costs += REJECTION_COST * (11 - j.getPriority());
            }
        }

        // Calculating the extra driving time to stop a vehicle
        activeVehicles.removeAll(newActiveVehicles);
        for (Id<DvrpVehicle> vehId : activeVehicles) {
            costs += realTimeVehicleInfoMap.get(vehId).divertableTime() - now;
        }

        return costs;
    }
}
