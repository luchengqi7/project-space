package org.matsim.project.prebookingStudy.jsprit;

import com.graphhopper.jsprit.core.problem.VehicleRoutingProblem;
import com.graphhopper.jsprit.core.problem.cost.VehicleRoutingTransportCosts;
import com.graphhopper.jsprit.core.problem.job.Job;
import com.graphhopper.jsprit.core.problem.solution.SolutionCostCalculator;
import com.graphhopper.jsprit.core.problem.solution.VehicleRoutingProblemSolution;
import com.graphhopper.jsprit.core.problem.solution.route.VehicleRoute;
import com.graphhopper.jsprit.core.problem.solution.route.activity.BreakActivity;
import com.graphhopper.jsprit.core.problem.solution.route.activity.TourActivity;

import org.matsim.contrib.drt.run.DrtConfigGroup;
import org.matsim.contrib.drt.run.MultiModeDrtConfigGroup;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.gbl.Gbl;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.project.prebookingStudy.jsprit.utils.StatisticCollectorForOF;

import java.nio.file.Path;
import java.util.Map;

import org.apache.log4j.Logger;

public class MySolutionCostCalculatorFactory {

    public enum ObjectiveFunctionType {JspritDefault, TT, TD, WT, TTTD, TTWT, TTWTTD, OnTimeArrival, OnTimeArrivalPlusTD}

    private static final Logger LOG = Logger.getLogger(MySolutionCostCalculatorFactory.class);

    public SolutionCostCalculator getObjectiveFunction(final VehicleRoutingProblem vrp, final double maxCosts, ObjectiveFunctionType objectiveFunctionType, MatsimDrtRequest2Jsprit matsimDrtRequest2Jsprit, boolean enableNetworkBasedCosts, int cacheSizeLimit) {
        //prepare to calculate the KPIs
        double serviceTimeInMatsim = 0;
        //Config config = ConfigUtils.loadConfig(matsimConfig.toString(), new MultiModeDrtConfigGroup());
        for (DrtConfigGroup drtCfg : MultiModeDrtConfigGroup.get(matsimDrtRequest2Jsprit.getConfig()).getModalElements()) {
            serviceTimeInMatsim = drtCfg.getStopDuration();
        }
        StatisticCollectorForOF statisticCollectorForOF;
        if (enableNetworkBasedCosts) {
            NetworkBasedDrtVrpCosts.Builder networkBasedDrtVrpCostsbuilder = new NetworkBasedDrtVrpCosts.Builder(matsimDrtRequest2Jsprit.getNetwork())
                    .enableCache(true)
                    .setCacheSizeLimit(cacheSizeLimit);
            VehicleRoutingTransportCosts transportCosts = networkBasedDrtVrpCostsbuilder.build();
            statisticCollectorForOF = new StatisticCollectorForOF(transportCosts, serviceTimeInMatsim);
            statisticCollectorForOF.setDesiredPickupTimeMap(matsimDrtRequest2Jsprit.getDesiredPickupTimeMap());
            statisticCollectorForOF.setDesiredDeliveryTimeMap(matsimDrtRequest2Jsprit.getDesiredDeliveryTimeMap());
        } else {
            statisticCollectorForOF = new StatisticCollectorForOF(serviceTimeInMatsim);
            statisticCollectorForOF.setDesiredPickupTimeMap(matsimDrtRequest2Jsprit.getDesiredPickupTimeMap());
            statisticCollectorForOF.setDesiredDeliveryTimeMap(matsimDrtRequest2Jsprit.getDesiredDeliveryTimeMap());
        }

        switch (objectiveFunctionType) {
            case JspritDefault:
                return getJspritDefaultObjectiveFunction(vrp, maxCosts);
            case TT:
                return getTTObjectiveFunction(vrp, maxCosts, statisticCollectorForOF);
            case TD:
                return getTDObjectiveFunction(vrp, maxCosts, statisticCollectorForOF);
            case WT:
                return getWTObjectiveFunction(vrp, maxCosts, statisticCollectorForOF);
            case TTTD:
                return getTTTDObjectiveFunction(vrp, maxCosts, statisticCollectorForOF);
            case TTWT:
                return getTTWTObjectiveFunction(vrp, maxCosts, statisticCollectorForOF);
            case TTWTTD:
                return getTTWTTDObjectiveFunction(vrp, maxCosts, statisticCollectorForOF);
            //school children related
            case OnTimeArrival:
                return getOnTimeArrivalObjectiveFunction(vrp, maxCosts, statisticCollectorForOF);
            case OnTimeArrivalPlusTD:
                return getOnTimeArrivalPlusTDObjectiveFunction(vrp, maxCosts, statisticCollectorForOF);
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
                        //ToDo: The used cost here is the TravelDisutility rather than travel time.
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
                        //throw new RuntimeException("There exists Breaks.");
                        LOG.info("************There exists Breaks! The vehicleId of this route is: " + route.getVehicle().getId() + "The number of breaks of this route is: " + route.getVehicle().getBreak() + "************");
                    }
                }
                if (solution.getUnassignedJobs().size() != 0){
                    //throw new RuntimeException("There exists unassgndJobs.");
                    LOG.info("************This solution has unassigned jobs! The number of unassigned jobs is: " + solution.getUnassignedJobs().size() + "************");
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

    private static SolutionCostCalculator getTTObjectiveFunction(final VehicleRoutingProblem vrp, final double maxCosts, StatisticCollectorForOF statisticCollectorForOF) {
        //if (objectiveFunction != null) return objectiveFunction;

        return new SolutionCostCalculator() {
            @Override
            public double getCosts(VehicleRoutingProblemSolution solution) {
                double costs = MySolutionCostCalculatorFactory.getDefaultCosts(solution, maxCosts);

                statisticCollectorForOF.statsCollector(vrp, solution);
                //add travel time
                costs += statisticCollectorForOF.getTravelTimeMap().values().stream().mapToDouble(x -> x).sum();
                return costs;
            }
        };
    }

    private static SolutionCostCalculator getTTTDObjectiveFunction(final VehicleRoutingProblem vrp, final double maxCosts, StatisticCollectorForOF statisticCollectorForOF) {
        //if (objectiveFunction != null) return objectiveFunction;

        return new SolutionCostCalculator() {
            @Override
            public double getCosts(VehicleRoutingProblemSolution solution) {
                double costs = MySolutionCostCalculatorFactory.getDefaultCosts(solution, maxCosts);

                statisticCollectorForOF.statsCollector(vrp, solution);
                //add travel time
                costs += statisticCollectorForOF.getTravelTimeMap().values().stream().mapToDouble(x -> x).sum();
                //add travel distance
                costs += statisticCollectorForOF.getPassengerTraveledDistanceMap().values().stream().mapToDouble(x -> x).sum();
                return costs;
            }
        };
    }

    private static SolutionCostCalculator getTDObjectiveFunction(final VehicleRoutingProblem vrp, final double maxCosts, StatisticCollectorForOF statisticCollectorForOF) {
        //if (objectiveFunction != null) return objectiveFunction;

        return new SolutionCostCalculator() {
            @Override
            public double getCosts(VehicleRoutingProblemSolution solution) {
                double costs = MySolutionCostCalculatorFactory.getDefaultCosts(solution, maxCosts);

                statisticCollectorForOF.statsCollector(vrp, solution);
                //add travel distance
                costs += statisticCollectorForOF.getPassengerTraveledDistanceMap().values().stream().mapToDouble(x -> x).sum();
                return costs;
            }
        };
    }

    private static SolutionCostCalculator getWTObjectiveFunction(final VehicleRoutingProblem vrp, final double maxCosts, StatisticCollectorForOF statisticCollectorForOF) {
        //if (objectiveFunction != null) return objectiveFunction;

        return new SolutionCostCalculator() {
            @Override
            public double getCosts(VehicleRoutingProblemSolution solution) {
                double costs = MySolutionCostCalculatorFactory.getDefaultCosts(solution, maxCosts);

                statisticCollectorForOF.statsCollector(vrp, solution);
                //add waiting time
                costs += statisticCollectorForOF.getWaitingTimeMap().values().stream().mapToDouble(x -> x).sum();
                return costs;
            }
        };
    }

    private static SolutionCostCalculator getTTWTObjectiveFunction(final VehicleRoutingProblem vrp, final double maxCosts, StatisticCollectorForOF statisticCollectorForOF) {
        //if (objectiveFunction != null) return objectiveFunction;

        return new SolutionCostCalculator() {
            @Override
            public double getCosts(VehicleRoutingProblemSolution solution) {
                double costs = MySolutionCostCalculatorFactory.getDefaultCosts(solution, maxCosts);

                statisticCollectorForOF.statsCollector(vrp, solution);
                //add travel time
                costs += statisticCollectorForOF.getTravelTimeMap().values().stream().mapToDouble(x -> x).sum();
                //add waiting time
                costs += statisticCollectorForOF.getWaitingTimeMap().values().stream().mapToDouble(x -> x).sum();
                return costs;
            }
        };
    }

    private static SolutionCostCalculator getTTWTTDObjectiveFunction(final VehicleRoutingProblem vrp, final double maxCosts, StatisticCollectorForOF statisticCollectorForOF) {
        //if (objectiveFunction != null) return objectiveFunction;

        return new SolutionCostCalculator() {
            @Override
            public double getCosts(VehicleRoutingProblemSolution solution) {
                double costs = MySolutionCostCalculatorFactory.getDefaultCosts(solution, maxCosts);

                statisticCollectorForOF.statsCollector(vrp, solution);
                //add travel time
                costs += statisticCollectorForOF.getTravelTimeMap().values().stream().mapToDouble(x -> x).sum();
                //add waiting time
                costs += statisticCollectorForOF.getWaitingTimeMap().values().stream().mapToDouble(x -> x).sum();
                //add travel distance
                costs += statisticCollectorForOF.getPassengerTraveledDistanceMap().values().stream().mapToDouble(x -> x).sum();
                return costs;
            }
        };
    }

    private static SolutionCostCalculator getOnTimeArrivalObjectiveFunction(final VehicleRoutingProblem vrp, final double maxCosts, StatisticCollectorForOF statisticCollectorForOF) {
        //if (objectiveFunction != null) return objectiveFunction;

        return new SolutionCostCalculator() {
            @Override
            public double getCosts(VehicleRoutingProblemSolution solution) {
                double costs = MySolutionCostCalculatorFactory.getDefaultCosts(solution, maxCosts);

                statisticCollectorForOF.statsCollector(vrp, solution);
                double factor = 20.;
                //add penalty for early/late arrival
                //ToDo: maybe 15 minutes earlier is better than on-time arrival?
                for (Map.Entry<String, Double> entry : statisticCollectorForOF.getDeliveryTimeMap().entrySet()) {
                    double timeOffset = statisticCollectorForOF.getDesiredDeliveryTimeMap().get(entry.getKey()) - entry.getValue();
                    costs += factor * timeOffset;
                }
                return costs;
            }
        };
    }

    private static SolutionCostCalculator getOnTimeArrivalPlusTDObjectiveFunction(final VehicleRoutingProblem vrp, final double maxCosts, StatisticCollectorForOF statisticCollectorForOF) {
        //if (objectiveFunction != null) return objectiveFunction;

        return new SolutionCostCalculator() {
            @Override
            public double getCosts(VehicleRoutingProblemSolution solution) {
                double costs = MySolutionCostCalculatorFactory.getDefaultCosts(solution, maxCosts);

                statisticCollectorForOF.statsCollector(vrp, solution);
                double factor = 20.;
                //add penalty for early/late arrival
                //ToDo: maybe 15 minutes earlier is better than on-time arrival?
                for (Map.Entry<String, Double> entry : statisticCollectorForOF.getDeliveryTimeMap().entrySet()) {
                    double timeOffset = statisticCollectorForOF.getDesiredDeliveryTimeMap().get(entry.getKey()) - entry.getValue();
                    costs += factor * timeOffset;
                }
                //add travel distance
                costs += statisticCollectorForOF.getPassengerTraveledDistanceMap().values().stream().mapToDouble(x -> x).sum();
                return costs;
            }
        };
    }

    private static double getDefaultCosts(VehicleRoutingProblemSolution solution, double maxCosts) {
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
                //throw new RuntimeException("There exists Breaks.");
                LOG.info("************There exists Breaks! The vehicleId of this route is: " + route.getVehicle().getId() + "The number of breaks of this route is: " + route.getVehicle().getBreak() + "************");
            }
        }
        if (solution.getUnassignedJobs().size() != 0){
            //throw new RuntimeException("There exists unassgndJobs.");
            LOG.info("************This solution has unassigned jobs! The number of unassigned jobs is: " + solution.getUnassignedJobs().size() + "************");
        }
        for(Job j : solution.getUnassignedJobs()){
            costs += maxCosts * 2 * (11 - j.getPriority());
        }
        return costs;
    }
}
