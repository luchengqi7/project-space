package org.matsim.project.prebookingStudy.jsprit;

import com.graphhopper.jsprit.core.problem.VehicleRoutingProblem;
import com.graphhopper.jsprit.core.problem.cost.VehicleRoutingTransportCosts;
import com.graphhopper.jsprit.core.problem.job.Job;
import com.graphhopper.jsprit.core.problem.solution.SolutionCostCalculator;
import com.graphhopper.jsprit.core.problem.solution.VehicleRoutingProblemSolution;
import com.graphhopper.jsprit.core.problem.solution.route.VehicleRoute;
import com.graphhopper.jsprit.core.problem.solution.route.activity.BreakActivity;
import com.graphhopper.jsprit.core.problem.solution.route.activity.TourActivity;

import org.matsim.core.config.ConfigUtils;
import org.matsim.core.gbl.Gbl;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.project.prebookingStudy.jsprit.utils.StatisticUtils;

import java.nio.file.Path;

public class MySolutionCostCalculatorFactory {

    public enum ObjectiveFunctionType {JspritDefaultObjectiveFunction, TTObjectiveFunction, TDObjectiveFunction, TTTDObjectiveFunction}

    public static SolutionCostCalculator getObjectiveFunction(final VehicleRoutingProblem vrp, final double maxCosts, ObjectiveFunctionType objectiveFunctionType, Path matsimConfig, boolean enableNetworkBasedCosts, int cacheSizeLimit) {
        //prepare to calculate the KPIs
        StatisticUtils statisticUtilsForOF;
        if (enableNetworkBasedCosts) {
            NetworkBasedDrtVrpCosts.Builder networkBasedDrtVrpCostsbuilder = new NetworkBasedDrtVrpCosts.Builder(ScenarioUtils.loadScenario(ConfigUtils.loadConfig(matsimConfig.toString())).getNetwork())
                    .enableCache(true)
                    .setCacheSizeLimit(cacheSizeLimit);
            VehicleRoutingTransportCosts transportCosts = networkBasedDrtVrpCostsbuilder.build();
            statisticUtilsForOF = new StatisticUtils(transportCosts);
        } else {
            statisticUtilsForOF = new StatisticUtils();
        }

        switch (objectiveFunctionType) {
            case JspritDefaultObjectiveFunction:
                return getJspritDefaultObjectiveFunction(vrp, maxCosts);
            case TTObjectiveFunction:
                return getTTObjectiveFunction(vrp, maxCosts, statisticUtilsForOF);
            case TDObjectiveFunction:
                return getTDObjectiveFunction(vrp, maxCosts, statisticUtilsForOF);
            case TTTDObjectiveFunction:
                return getTTTDObjectiveFunction(vrp, maxCosts, statisticUtilsForOF);
            default:
                throw new RuntimeException(Gbl.NOT_IMPLEMENTED);
        }
    }

    private static SolutionCostCalculator getJspritDefaultObjectiveFunction(final VehicleRoutingProblem vrp, final double maxCosts) {
        //if (objectiveFunction != null) return objectiveFunction;

        return new SolutionCostCalculator() {
            @Override
            public double getCosts(VehicleRoutingProblemSolution solution) {
                double costs = 0.;

                for (VehicleRoute route : solution.getRoutes()) {
                    costs += route.getVehicle().getType().getVehicleCostParams().fix;
                    boolean hasBreak = false;
                    TourActivity prevAct = route.getStart();
                    for (TourActivity act : route.getActivities()) {
                        if (act instanceof BreakActivity) hasBreak = true;
                        costs += vrp.getTransportCosts().getTransportCost(prevAct.getLocation(), act.getLocation(), prevAct.getEndTime(), route.getDriver(), route.getVehicle());
                        costs += vrp.getActivityCosts().getActivityCost(act, act.getArrTime(), route.getDriver(), route.getVehicle());
                        prevAct = act;
                    }
                    costs += vrp.getTransportCosts().getTransportCost(prevAct.getLocation(), route.getEnd().getLocation(), prevAct.getEndTime(), route.getDriver(), route.getVehicle());
                    if (route.getVehicle().getBreak() != null) {
                        if (!hasBreak) {
                            //break defined and required but not assigned penalty
                            if (route.getEnd().getArrTime() > route.getVehicle().getBreak().getTimeWindow().getEnd()) {
                                costs += 4 * (maxCosts * 2 + route.getVehicle().getBreak().getServiceDuration() * route.getVehicle().getType().getVehicleCostParams().perServiceTimeUnit);
                            }
                        }
                    }
                }
                for(Job j : solution.getUnassignedJobs()){
                    costs += maxCosts * 2 * (11 - j.getPriority());
                }
                return costs;
            }
        };
    }

    private static SolutionCostCalculator getTemplateObjectiveFunction(final VehicleRoutingProblem vrp, final double maxCosts) {
        //if (objectiveFunction != null) return objectiveFunction;

        return new SolutionCostCalculator() {
            @Override
            public double getCosts(VehicleRoutingProblemSolution solution) {
                double costs = 0.;

                for (VehicleRoute route : solution.getRoutes()) {
                    //costs += route.getVehicle().getType().getVehicleCostParams().fix;
                    boolean hasBreak = false;
                    TourActivity prevAct = route.getStart();
                    for (TourActivity act : route.getActivities()) {
                        if (act instanceof BreakActivity) hasBreak = true;
                        costs += vrp.getTransportCosts().getTransportCost(prevAct.getLocation(), act.getLocation(), prevAct.getEndTime(), route.getDriver(), route.getVehicle());
                        //costs += vrp.getActivityCosts().getActivityCost(act, act.getArrTime(), route.getDriver(), route.getVehicle());
                        prevAct = act;
                    }
                    costs += vrp.getTransportCosts().getTransportCost(prevAct.getLocation(), route.getEnd().getLocation(), prevAct.getEndTime(), route.getDriver(), route.getVehicle());
                    if (route.getVehicle().getBreak() != null) {
                        if (!hasBreak) {
                            //break defined and required but not assigned penalty
                            if (route.getEnd().getArrTime() > route.getVehicle().getBreak().getTimeWindow().getEnd()) {
                                costs += 4 * (maxCosts * 2 + route.getVehicle().getBreak().getServiceDuration() * route.getVehicle().getType().getVehicleCostParams().perServiceTimeUnit);
                            }
                        }
                        throw new RuntimeException("There exists Breaks.");
                    }
                }
                if (solution.getUnassignedJobs().size() != 0){
                    throw new RuntimeException("There exists unassgndJobs.");
                }
                for(Job j : solution.getUnassignedJobs()){
                    costs += maxCosts * 2 * (11 - j.getPriority());
                }
                return costs;
            }
        };
    }

    private static SolutionCostCalculator getTTObjectiveFunction(final VehicleRoutingProblem vrp, final double maxCosts, StatisticUtils statisticUtilsForOF) {
        //if (objectiveFunction != null) return objectiveFunction;

        return new SolutionCostCalculator() {
            @Override
            public double getCosts(VehicleRoutingProblemSolution solution) {
                double costs = 0.;

                for (VehicleRoute route : solution.getRoutes()) {
                    //costs += route.getVehicle().getType().getVehicleCostParams().fix;
                    boolean hasBreak = false;
                    TourActivity prevAct = route.getStart();
                    for (TourActivity act : route.getActivities()) {
                        if (act instanceof BreakActivity) hasBreak = true;
                        //costs += vrp.getTransportCosts().getTransportCost(prevAct.getLocation(), act.getLocation(), prevAct.getEndTime(), route.getDriver(), route.getVehicle());
                        //costs += vrp.getActivityCosts().getActivityCost(act, act.getArrTime(), route.getDriver(), route.getVehicle());
                        prevAct = act;
                    }
                    //costs += vrp.getTransportCosts().getTransportCost(prevAct.getLocation(), route.getEnd().getLocation(), prevAct.getEndTime(), route.getDriver(), route.getVehicle());
                    if (route.getVehicle().getBreak() != null) {
                        if (!hasBreak) {
                            //break defined and required but not assigned penalty
                            if (route.getEnd().getArrTime() > route.getVehicle().getBreak().getTimeWindow().getEnd()) {
                                costs += 4 * (maxCosts * 2 + route.getVehicle().getBreak().getServiceDuration() * route.getVehicle().getType().getVehicleCostParams().perServiceTimeUnit);
                            }
                        }
                        throw new RuntimeException("There exists Breaks.");
                    }
                }
                if (solution.getUnassignedJobs().size() != 0){
                    throw new RuntimeException("There exists unassgndJobs.");
                }
                for(Job j : solution.getUnassignedJobs()){
                    costs += maxCosts * 2 * (11 - j.getPriority());
                }

                //add travel time
                //ToDo: The used cost here is the travel time rather than TravelDisutility.
                statisticUtilsForOF.statsCollector(vrp, solution);
                costs += statisticUtilsForOF.getTravelTimeMap().values().stream().mapToDouble(x -> x).sum();
                return costs;
            }
        };
    }

    private static SolutionCostCalculator getTTTDObjectiveFunction(final VehicleRoutingProblem vrp, final double maxCosts, StatisticUtils statisticUtilsForOF) {
        //if (objectiveFunction != null) return objectiveFunction;

        return new SolutionCostCalculator() {
            @Override
            public double getCosts(VehicleRoutingProblemSolution solution) {
                double costs = 0.;

                for (VehicleRoute route : solution.getRoutes()) {
                    //costs += route.getVehicle().getType().getVehicleCostParams().fix;
                    boolean hasBreak = false;
                    TourActivity prevAct = route.getStart();
                    for (TourActivity act : route.getActivities()) {
                        if (act instanceof BreakActivity) hasBreak = true;
                        //costs += vrp.getTransportCosts().getTransportCost(prevAct.getLocation(), act.getLocation(), prevAct.getEndTime(), route.getDriver(), route.getVehicle());
                        //costs += vrp.getActivityCosts().getActivityCost(act, act.getArrTime(), route.getDriver(), route.getVehicle());
                        prevAct = act;
                    }
                    //costs += vrp.getTransportCosts().getTransportCost(prevAct.getLocation(), route.getEnd().getLocation(), prevAct.getEndTime(), route.getDriver(), route.getVehicle());
                    if (route.getVehicle().getBreak() != null) {
                        if (!hasBreak) {
                            //break defined and required but not assigned penalty
                            if (route.getEnd().getArrTime() > route.getVehicle().getBreak().getTimeWindow().getEnd()) {
                                costs += 4 * (maxCosts * 2 + route.getVehicle().getBreak().getServiceDuration() * route.getVehicle().getType().getVehicleCostParams().perServiceTimeUnit);
                            }
                        }
                        throw new RuntimeException("There exists Breaks.");
                    }
                }
                if (solution.getUnassignedJobs().size() != 0){
                    throw new RuntimeException("There exists unassgndJobs.");
                }
                for(Job j : solution.getUnassignedJobs()){
                    costs += maxCosts * 2 * (11 - j.getPriority());
                }

                //add travel time
                //ToDo: The used cost here is the travel time rather than TravelDisutility.
                statisticUtilsForOF.statsCollector(vrp, solution);
                costs += statisticUtilsForOF.getTravelTimeMap().values().stream().mapToDouble(x -> x).sum();
                //add travel distance
                costs += statisticUtilsForOF.getPassengerTraveledDistanceMap().values().stream().mapToDouble(x -> x).sum();
                return costs;
            }
        };
    }

    private SolutionCostCalculator getTDObjectiveFunction(final VehicleRoutingProblem vrp, final double maxCosts, StatisticUtils statisticUtilsForOF) {
        //if (objectiveFunction != null) return objectiveFunction;

        return new SolutionCostCalculator() {
            @Override
            public double getCosts(VehicleRoutingProblemSolution solution) {
                double costs = MySolutionCostCalculatorFactory.this.getCosts(solution, maxCosts);

                //add travel distance
                statisticUtilsForOF.statsCollector(vrp, solution);
                costs += statisticUtilsForOF.getPassengerTraveledDistanceMap().values().stream().mapToDouble(x -> x).sum();
                return costs;
            }
        };
    }

    private static double getCosts(VehicleRoutingProblemSolution solution, double maxCosts) {
        double costs = 0.;

        for (VehicleRoute route : solution.getRoutes()) {
            //costs += route.getVehicle().getType().getVehicleCostParams().fix;
            boolean hasBreak = false;
            TourActivity prevAct = route.getStart();
            for (TourActivity act : route.getActivities()) {
                if (act instanceof BreakActivity) hasBreak = true;
                //costs += vrp.getTransportCosts().getTransportCost(prevAct.getLocation(), act.getLocation(), prevAct.getEndTime(), route.getDriver(), route.getVehicle());
                //costs += vrp.getActivityCosts().getActivityCost(act, act.getArrTime(), route.getDriver(), route.getVehicle());
                prevAct = act;
            }
            //costs += vrp.getTransportCosts().getTransportCost(prevAct.getLocation(), route.getEnd().getLocation(), prevAct.getEndTime(), route.getDriver(), route.getVehicle());
            if (route.getVehicle().getBreak() != null) {
                if (!hasBreak) {
                    //break defined and required but not assigned penalty
                    if (route.getEnd().getArrTime() > route.getVehicle().getBreak().getTimeWindow().getEnd()) {
                        costs += 4 * (maxCosts * 2 + route.getVehicle().getBreak().getServiceDuration() * route.getVehicle().getType().getVehicleCostParams().perServiceTimeUnit);
                    }
                }
                throw new RuntimeException("There exists Breaks.");
            }
        }
        if (solution.getUnassignedJobs().size() != 0){
            throw new RuntimeException("There exists unassgndJobs.");
        }
        for(Job j : solution.getUnassignedJobs()){
            costs += maxCosts * 2 * (11 - j.getPriority());
        }
        return costs;
    }
}
