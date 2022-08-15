package org.matsim.project.drtSchoolTransportStudy.jsprit;

import com.graphhopper.jsprit.core.problem.VehicleRoutingProblem;
import com.graphhopper.jsprit.core.problem.cost.VehicleRoutingTransportCosts;
import com.graphhopper.jsprit.core.problem.job.Job;
import com.graphhopper.jsprit.core.problem.solution.SolutionCostCalculator;
import com.graphhopper.jsprit.core.problem.solution.VehicleRoutingProblemSolution;
import com.graphhopper.jsprit.core.problem.solution.route.VehicleRoute;
import com.graphhopper.jsprit.core.problem.solution.route.activity.BreakActivity;
import com.graphhopper.jsprit.core.problem.solution.route.activity.TourActivity;

import com.graphhopper.jsprit.core.util.EuclideanCosts;
import org.matsim.contrib.drt.run.DrtConfigGroup;
import org.matsim.contrib.drt.run.MultiModeDrtConfigGroup;
import org.matsim.core.config.Config;
import org.matsim.core.gbl.Gbl;
import org.matsim.project.drtSchoolTransportStudy.jsprit.utils.StatisticCollectorForOF;

import java.util.Map;

import org.apache.log4j.Logger;
import org.matsim.project.drtSchoolTransportStudy.jsprit.utils.TransportCostUtils;

public class MySolutionCostCalculatorFactory {

    public enum ObjectiveFunctionType {JspritDefault, /*JspritDefaultMinusNoVeh, JspritDefaultPlusLatePickup, JspritDefaultPlusLatePickupMinusNoVeh, */Jsprit, JspritMinusNoVeh, JspritPlusLatePickup, JspritPlusLatePickupMinusNoVeh, TT, TTPlusNoVeh, TD, WT, TTTD, TTWT, TTWTTD, OnTimeArrival, OnTimeArrivalPlusTD, DD, DDPlusNoVeh, DT, DTPlusNoVeh, NoVeh, TTPlusDDPlusNoVeh, TTPlusDD, IVT, IVTPlusNoVeh, IVTPlusDDPlusNoVeh, IVTPlusDD, LatePickup, LatePickupPlusNoVeh}

    private static final Logger LOG = Logger.getLogger(MySolutionCostCalculatorFactory.class);

    public SolutionCostCalculator getObjectiveFunction(final VehicleRoutingProblem vrp, final double maxCosts, ObjectiveFunctionType objectiveFunctionType, Config config, VehicleRoutingTransportCosts transportCosts) {
        //prepare to calculate the KPIs
        double serviceTimeInMatsim = 0;
        //Config config = ConfigUtils.loadConfig(matsimConfig.toString(), new MultiModeDrtConfigGroup());
        for (DrtConfigGroup drtCfg : MultiModeDrtConfigGroup.get(config).getModalElements()) {
            serviceTimeInMatsim = drtCfg.getStopDuration();
        }
        StatisticCollectorForOF statisticCollectorForOF;
        if (transportCosts instanceof MatrixBasedVrpCosts) {
            statisticCollectorForOF = new StatisticCollectorForOF(transportCosts, serviceTimeInMatsim);
        } else if(transportCosts instanceof EuclideanCosts) {
            statisticCollectorForOF = new StatisticCollectorForOF(serviceTimeInMatsim);
        } else{
            throw new RuntimeException("MatsimVrpCostsCaculatorType can either be EuclideanCosts or NetworkBased/MatrixBased!");
        }

        switch (objectiveFunctionType) {
            case JspritDefault:
                return getJspritDefaultObjectiveFunction(vrp, maxCosts);
/*            case JspritDefaultMinusNoVeh:
                return getJspritDefaultMinusNoVehObjectiveFunction(vrp, maxCosts);
            case JspritDefaultPlusLatePickup:
                return getJspritDefaultPlusLatePickupObjectiveFunction(vrp, maxCosts, statisticCollectorForOF, serviceTimeInMatsim);
            case JspritDefaultPlusLatePickupMinusNoVeh:
                return getJspritDefaultPlusLatePickupMinusNoVehObjectiveFunction(vrp, maxCosts, statisticCollectorForOF, serviceTimeInMatsim);*/
            //jsprit default idea but using generalised transport costs
            case Jsprit:
                return getJspritObjectiveFunction(vrp, maxCosts);
            case JspritMinusNoVeh:
                return getJspritMinusNoVehObjectiveFunction(vrp, maxCosts);
            case JspritPlusLatePickup:
                return getJspritPlusLatePickupObjectiveFunction(vrp, maxCosts, statisticCollectorForOF, serviceTimeInMatsim);
            case JspritPlusLatePickupMinusNoVeh:
                return getJspritPlusLatePickupMinusNoVehObjectiveFunction(vrp, maxCosts, statisticCollectorForOF, serviceTimeInMatsim);
            //customised objective functions (using generalised transport costs)
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
            case TTPlusNoVeh:
                return getTTPlusNoVehObjectiveFunction(vrp, maxCosts, statisticCollectorForOF);
            //school children related
            case OnTimeArrival:
                return getOnTimeArrivalObjectiveFunction(vrp, maxCosts, statisticCollectorForOF);
            case OnTimeArrivalPlusTD:
                return getOnTimeArrivalPlusTDObjectiveFunction(vrp, maxCosts, statisticCollectorForOF);
            case DD:
                return getDDObjectiveFunction(vrp, maxCosts, statisticCollectorForOF);
            case DDPlusNoVeh:
                return getDDPlusNoVehObjectiveFunction(vrp, maxCosts, statisticCollectorForOF);
            case DT:
                return getDTObjectiveFunction(vrp, maxCosts, statisticCollectorForOF);
            case DTPlusNoVeh:
                return getDTPlusNoVehObjectiveFunction(vrp, maxCosts, statisticCollectorForOF);
            case NoVeh:
                return getNoVehObjectiveFunction(vrp, maxCosts);
            case TTPlusDDPlusNoVeh:
                return getTTPlusDDPlusNoVehObjectiveFunction(vrp, maxCosts, statisticCollectorForOF);
            case TTPlusDD:
                return getTTPlusDDObjectiveFunction(vrp, maxCosts, statisticCollectorForOF);
            case IVT:
                return getIVTObjectiveFunction(vrp, maxCosts, statisticCollectorForOF);
            case IVTPlusNoVeh:
                return getIVTPlusNoVehObjectiveFunction(vrp, maxCosts, statisticCollectorForOF);
            case IVTPlusDDPlusNoVeh:
                return getIVTPlusDDPlusNoVehObjectiveFunction(vrp, maxCosts, statisticCollectorForOF);
            case IVTPlusDD:
                return getIVTPlusDDObjectiveFunction(vrp, maxCosts, statisticCollectorForOF);
            case LatePickup:
                return getLatePickupObjectiveFunction(vrp, maxCosts, statisticCollectorForOF, serviceTimeInMatsim);
            case LatePickupPlusNoVeh:
                return getLatePickupPlusNoVehObjectiveFunction(vrp, maxCosts, statisticCollectorForOF, serviceTimeInMatsim);
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

    private static SolutionCostCalculator getJspritDefaultMinusNoVehObjectiveFunction(final VehicleRoutingProblem vrp, final double maxCosts) {
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

    private static SolutionCostCalculator getJspritDefaultPlusLatePickupObjectiveFunction(final VehicleRoutingProblem vrp, final double maxCosts, StatisticCollectorForOF statisticCollectorForOF, double serviceTimeInMatsim) {
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

                //add costs for late pickups
                statisticCollectorForOF.statsCollector(vrp, solution);
                //add penalty for too early pickups
                for (Map.Entry<String, Double> entry : statisticCollectorForOF.getPickupTimeMap().entrySet()) {
                    double timeOffset = statisticCollectorForOF.getDesiredDeliveryTimeMap().get(entry.getKey()) - entry.getValue();
                    costs += timeOffset /*- statisticCollectorForOF.getInVehicleTimeMap().get(entry.getKey())*/;
                }

                return costs;
            }
        };
    }

    private static SolutionCostCalculator getJspritDefaultPlusLatePickupMinusNoVehObjectiveFunction(final VehicleRoutingProblem vrp, final double maxCosts, StatisticCollectorForOF statisticCollectorForOF, double serviceTimeInMatsim) {
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

                //add costs for late pickups
                statisticCollectorForOF.statsCollector(vrp, solution);
                //add penalty for too early pickups
                for (Map.Entry<String, Double> entry : statisticCollectorForOF.getPickupTimeMap().entrySet()) {
                    double timeOffset = statisticCollectorForOF.getDesiredDeliveryTimeMap().get(entry.getKey()) - entry.getValue();
                    costs += timeOffset /*- statisticCollectorForOF.getInVehicleTimeMap().get(entry.getKey())*/;
                }

                return costs;
            }
        };
    }

    //jsprit default idea but using generalised transport costs
    private static SolutionCostCalculator getJspritObjectiveFunction(final VehicleRoutingProblem vrp, final double maxCosts) {
        //if (objectiveFunction != null) return objectiveFunction;

        return new SolutionCostCalculator() {
            @Override
            public double getCosts(VehicleRoutingProblemSolution solution) {
                double costs = 0.;

                for (VehicleRoute route : solution.getRoutes()) {
                    costs += TransportCostUtils.getVehicleFixCostPerDay();
                    boolean hasBreak = false;
                    TourActivity prevAct = route.getStart();
                    for (TourActivity act : route.getActivities()) {
                        if (act instanceof BreakActivity) hasBreak = true;
                        //ToDo: The used cost here is the TravelDisutility rather than travel time.
                        costs += TransportCostUtils.getDriveCostRate() * vrp.getTransportCosts().getTransportCost(prevAct.getLocation(), act.getLocation(), prevAct.getEndTime(), route.getDriver(), route.getVehicle());
                        costs += TransportCostUtils.getDriveCostRate() * vrp.getActivityCosts().getActivityCost(act, act.getArrTime(), route.getDriver(), route.getVehicle());
                        prevAct = act;
                    }
                    costs += TransportCostUtils.getDriveCostRate() * vrp.getTransportCosts().getTransportCost(prevAct.getLocation(), route.getEnd().getLocation(), prevAct.getEndTime(), route.getDriver(), route.getVehicle());
                    if (route.getVehicle().getBreak() != null) {
                        if (!hasBreak) {
                            //break defined and required but not assigned penalty
                            if (route.getEnd().getArrTime() > route.getVehicle().getBreak().getTimeWindow().getEnd()) {
                                costs += 4 * (maxCosts * 2 + route.getVehicle().getBreak().getServiceDuration() * TransportCostUtils.getDriveCostRate());
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

    //jsprit default idea but using generalised transport costs
    private static SolutionCostCalculator getJspritMinusNoVehObjectiveFunction(final VehicleRoutingProblem vrp, final double maxCosts) {
        //if (objectiveFunction != null) return objectiveFunction;

        return new SolutionCostCalculator() {
            @Override
            public double getCosts(VehicleRoutingProblemSolution solution) {
                double costs = 0.;

                for (VehicleRoute route : solution.getRoutes()) {
                    //costs += TransportCostUtils.getVehicleFixCostPerDay();
                    boolean hasBreak = false;
                    TourActivity prevAct = route.getStart();
                    for (TourActivity act : route.getActivities()) {
                        if (act instanceof BreakActivity) hasBreak = true;
                        //ToDo: The used cost here is the TravelDisutility rather than travel time.
                        costs += TransportCostUtils.getDriveCostRate() * vrp.getTransportCosts().getTransportCost(prevAct.getLocation(), act.getLocation(), prevAct.getEndTime(), route.getDriver(), route.getVehicle());
                        costs += TransportCostUtils.getDriveCostRate() * vrp.getActivityCosts().getActivityCost(act, act.getArrTime(), route.getDriver(), route.getVehicle());
                        prevAct = act;
                    }
                    costs += TransportCostUtils.getDriveCostRate() * vrp.getTransportCosts().getTransportCost(prevAct.getLocation(), route.getEnd().getLocation(), prevAct.getEndTime(), route.getDriver(), route.getVehicle());
                    if (route.getVehicle().getBreak() != null) {
                        if (!hasBreak) {
                            //break defined and required but not assigned penalty
                            if (route.getEnd().getArrTime() > route.getVehicle().getBreak().getTimeWindow().getEnd()) {
                                costs += 4 * (maxCosts * 2 + route.getVehicle().getBreak().getServiceDuration() * TransportCostUtils.getDriveCostRate());
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

    //jsprit default idea but using generalised transport costs
    private static SolutionCostCalculator getJspritPlusLatePickupObjectiveFunction(final VehicleRoutingProblem vrp, final double maxCosts, StatisticCollectorForOF statisticCollectorForOF, double serviceTimeInMatsim) {
        //if (objectiveFunction != null) return objectiveFunction;

        return new SolutionCostCalculator() {
            @Override
            public double getCosts(VehicleRoutingProblemSolution solution) {
                double costs = 0.;

                for (VehicleRoute route : solution.getRoutes()) {
                    costs += TransportCostUtils.getVehicleFixCostPerDay();
                    boolean hasBreak = false;
                    TourActivity prevAct = route.getStart();
                    for (TourActivity act : route.getActivities()) {
                        if (act instanceof BreakActivity) hasBreak = true;
                        //ToDo: The used cost here is the TravelDisutility rather than travel time.
                        costs += TransportCostUtils.getDriveCostRate() * vrp.getTransportCosts().getTransportCost(prevAct.getLocation(), act.getLocation(), prevAct.getEndTime(), route.getDriver(), route.getVehicle());
                        costs += TransportCostUtils.getDriveCostRate() * vrp.getActivityCosts().getActivityCost(act, act.getArrTime(), route.getDriver(), route.getVehicle());
                        prevAct = act;
                    }
                    costs += TransportCostUtils.getDriveCostRate() * vrp.getTransportCosts().getTransportCost(prevAct.getLocation(), route.getEnd().getLocation(), prevAct.getEndTime(), route.getDriver(), route.getVehicle());
                    if (route.getVehicle().getBreak() != null) {
                        if (!hasBreak) {
                            //break defined and required but not assigned penalty
                            if (route.getEnd().getArrTime() > route.getVehicle().getBreak().getTimeWindow().getEnd()) {
                                costs += 4 * (maxCosts * 2 + route.getVehicle().getBreak().getServiceDuration() * TransportCostUtils.getDriveCostRate());
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

                //add costs for late pickups
                statisticCollectorForOF.statsCollector(vrp, solution);
                //add penalty for too early pickups
                for (Map.Entry<String, Double> entry : statisticCollectorForOF.getPickupTimeMap().entrySet()) {
                    if(statisticCollectorForOF.getDesiredPickupTimeMap().get(entry.getKey()) > entry.getValue()) {
                        costs += TransportCostUtils.getDriveCostRate() * (statisticCollectorForOF.getDesiredPickupTimeMap().get(entry.getKey()) - entry.getValue());
                        double timeOffset = statisticCollectorForOF.getDesiredDeliveryTimeMap().get(entry.getKey()) - statisticCollectorForOF.getDesiredPickupTimeMap().get(entry.getKey());
                        costs += TransportCostUtils.getStandardActivityDeviationCosts() * (timeOffset /*- statisticCollectorForOF.getInVehicleTimeMap().get(entry.getKey())*/);
                    }else{
                        double timeOffset = statisticCollectorForOF.getDesiredDeliveryTimeMap().get(entry.getKey()) - entry.getValue();
                        costs += TransportCostUtils.getStandardActivityDeviationCosts() * (timeOffset /*- statisticCollectorForOF.getInVehicleTimeMap().get(entry.getKey())*/);
                    }
                }

                return costs;
            }
        };
    }

    //jsprit default idea but using generalised transport costs
    private static SolutionCostCalculator getJspritPlusLatePickupMinusNoVehObjectiveFunction(final VehicleRoutingProblem vrp, final double maxCosts, StatisticCollectorForOF statisticCollectorForOF, double serviceTimeInMatsim) {
        //if (objectiveFunction != null) return objectiveFunction;

        return new SolutionCostCalculator() {
            @Override
            public double getCosts(VehicleRoutingProblemSolution solution) {
                double costs = 0.;

                for (VehicleRoute route : solution.getRoutes()) {
                    //costs += TransportCostUtils.getVehicleFixCostPerDay();
                    boolean hasBreak = false;
                    TourActivity prevAct = route.getStart();
                    for (TourActivity act : route.getActivities()) {
                        if (act instanceof BreakActivity) hasBreak = true;
                        //ToDo: The used cost here is the TravelDisutility rather than travel time.
                        costs += TransportCostUtils.getDriveCostRate() * vrp.getTransportCosts().getTransportCost(prevAct.getLocation(), act.getLocation(), prevAct.getEndTime(), route.getDriver(), route.getVehicle());
                        costs += TransportCostUtils.getDriveCostRate() * vrp.getActivityCosts().getActivityCost(act, act.getArrTime(), route.getDriver(), route.getVehicle());
                        prevAct = act;
                    }
                    costs += TransportCostUtils.getDriveCostRate() * vrp.getTransportCosts().getTransportCost(prevAct.getLocation(), route.getEnd().getLocation(), prevAct.getEndTime(), route.getDriver(), route.getVehicle());
                    if (route.getVehicle().getBreak() != null) {
                        if (!hasBreak) {
                            //break defined and required but not assigned penalty
                            if (route.getEnd().getArrTime() > route.getVehicle().getBreak().getTimeWindow().getEnd()) {
                                costs += 4 * (maxCosts * 2 + route.getVehicle().getBreak().getServiceDuration() * TransportCostUtils.getDriveCostRate());
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

                //add costs for late pickups
                statisticCollectorForOF.statsCollector(vrp, solution);
                //add penalty for too early pickups
                for (Map.Entry<String, Double> entry : statisticCollectorForOF.getPickupTimeMap().entrySet()) {
                    if(statisticCollectorForOF.getDesiredPickupTimeMap().get(entry.getKey()) > entry.getValue()) {
                        costs += TransportCostUtils.getDriveCostRate() * (statisticCollectorForOF.getDesiredPickupTimeMap().get(entry.getKey()) - entry.getValue());
                        double timeOffset = statisticCollectorForOF.getDesiredDeliveryTimeMap().get(entry.getKey()) - statisticCollectorForOF.getDesiredPickupTimeMap().get(entry.getKey());
                        costs += TransportCostUtils.getStandardActivityDeviationCosts() * (timeOffset /*- statisticCollectorForOF.getInVehicleTimeMap().get(entry.getKey())*/);
                    }else{
                        double timeOffset = statisticCollectorForOF.getDesiredDeliveryTimeMap().get(entry.getKey()) - entry.getValue();
                        costs += TransportCostUtils.getStandardActivityDeviationCosts() * (timeOffset /*- statisticCollectorForOF.getInVehicleTimeMap().get(entry.getKey())*/);
                    }
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
                costs += TransportCostUtils.getTravelTimeCosts() * statisticCollectorForOF.getTravelTimeMap().values().stream().mapToDouble(x -> x).sum();
                return costs;
            }
        };
    }

    private static SolutionCostCalculator getTTPlusNoVehObjectiveFunction(final VehicleRoutingProblem vrp, final double maxCosts, StatisticCollectorForOF statisticCollectorForOF) {
        //if (objectiveFunction != null) return objectiveFunction;

        return new SolutionCostCalculator() {
            @Override
            public double getCosts(VehicleRoutingProblemSolution solution) {
                double costs = MySolutionCostCalculatorFactory.getDefaultCosts(solution, maxCosts);

                statisticCollectorForOF.statsCollector(vrp, solution);
                //add travel time
                costs += TransportCostUtils.getTravelTimeCosts() * statisticCollectorForOF.getTravelTimeMap().values().stream().mapToDouble(x -> x).sum();
                //add used number of vehicles
                costs += TransportCostUtils.getVehicleCosts() * solution.getRoutes().size();
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
                costs += TransportCostUtils.getTravelTimeCosts() * statisticCollectorForOF.getTravelTimeMap().values().stream().mapToDouble(x -> x).sum();
                //add travel distance
                costs += TransportCostUtils.getTravelDistanceCosts() * statisticCollectorForOF.getPassengerTraveledDistanceMap().values().stream().mapToDouble(x -> x).sum();
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
                costs += TransportCostUtils.getTravelDistanceCosts() * statisticCollectorForOF.getPassengerTraveledDistanceMap().values().stream().mapToDouble(x -> x).sum();
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
                costs += TransportCostUtils.getWaitingTimeCosts() * statisticCollectorForOF.getWaitingTimeMap().values().stream().mapToDouble(x -> x).sum();
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
                costs += TransportCostUtils.getTravelTimeCosts() * statisticCollectorForOF.getTravelTimeMap().values().stream().mapToDouble(x -> x).sum();
                //add waiting time
                costs += TransportCostUtils.getWaitingTimeCosts() * statisticCollectorForOF.getWaitingTimeMap().values().stream().mapToDouble(x -> x).sum();
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
                costs += TransportCostUtils.getTravelTimeCosts() * statisticCollectorForOF.getTravelTimeMap().values().stream().mapToDouble(x -> x).sum();
                //add waiting time
                costs += TransportCostUtils.getWaitingTimeCosts() * statisticCollectorForOF.getWaitingTimeMap().values().stream().mapToDouble(x -> x).sum();
                //add travel distance
                costs += TransportCostUtils.getTravelDistanceCosts() * statisticCollectorForOF.getPassengerTraveledDistanceMap().values().stream().mapToDouble(x -> x).sum();
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
                //add penalty for early/late arrival
                //ToDo: maybe 15 minutes earlier is better than on-time arrival?
                for (Map.Entry<String, Double> entry : statisticCollectorForOF.getDeliveryTimeMap().entrySet()) {
                    double timeOffset = statisticCollectorForOF.getDesiredDeliveryTimeMap().get(entry.getKey()) - entry.getValue();
                    costs += TransportCostUtils.getStandardActivityDeviationCosts() * timeOffset;
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
                //add penalty for early/late arrival
                //ToDo: maybe 15 minutes earlier is better than on-time arrival?
                for (Map.Entry<String, Double> entry : statisticCollectorForOF.getDeliveryTimeMap().entrySet()) {
                    double timeOffset = statisticCollectorForOF.getDesiredDeliveryTimeMap().get(entry.getKey()) - entry.getValue();
                    costs += TransportCostUtils.getStandardActivityDeviationCosts() * timeOffset;
                }
                //add travel distance
                costs += TransportCostUtils.getTravelDistanceCosts() * statisticCollectorForOF.getPassengerTraveledDistanceMap().values().stream().mapToDouble(x -> x).sum();
                return costs;
            }
        };
    }

    private static SolutionCostCalculator getDDObjectiveFunction(final VehicleRoutingProblem vrp, final double maxCosts, StatisticCollectorForOF statisticCollectorForOF) {
        //if (objectiveFunction != null) return objectiveFunction;

        return new SolutionCostCalculator() {
            @Override
            public double getCosts(VehicleRoutingProblemSolution solution) {
                double costs = MySolutionCostCalculatorFactory.getDefaultCosts(solution, maxCosts);

                statisticCollectorForOF.statsCollector(vrp, solution);
                //add driven distance
                costs += TransportCostUtils.getDrivenDistanceCosts() * statisticCollectorForOF.getDrivenDistanceMap().values().stream().mapToDouble(x -> x).sum();
                return costs;
            }
        };
    }

    private static SolutionCostCalculator getDDPlusNoVehObjectiveFunction(final VehicleRoutingProblem vrp, final double maxCosts, StatisticCollectorForOF statisticCollectorForOF) {
        //if (objectiveFunction != null) return objectiveFunction;

        return new SolutionCostCalculator() {
            @Override
            public double getCosts(VehicleRoutingProblemSolution solution) {
                double costs = MySolutionCostCalculatorFactory.getDefaultCosts(solution, maxCosts);

                statisticCollectorForOF.statsCollector(vrp, solution);
                //add driven distance
                costs += TransportCostUtils.getDrivenDistanceCosts() * statisticCollectorForOF.getDrivenDistanceMap().values().stream().mapToDouble(x -> x).sum();
                //add used number of vehicles
                costs += TransportCostUtils.getVehicleCosts() * solution.getRoutes().size();
                return costs;
            }
        };
    }

    private static SolutionCostCalculator getDTObjectiveFunction(final VehicleRoutingProblem vrp, final double maxCosts, StatisticCollectorForOF statisticCollectorForOF) {
        //if (objectiveFunction != null) return objectiveFunction;

        return new SolutionCostCalculator() {
            @Override
            public double getCosts(VehicleRoutingProblemSolution solution) {
                double costs = MySolutionCostCalculatorFactory.getDefaultCosts(solution, maxCosts);

                statisticCollectorForOF.statsCollector(vrp, solution);
                //add driven time
                costs += TransportCostUtils.getDriveCostRate() * statisticCollectorForOF.getDrivenTimeMap().values().stream().mapToDouble(x -> x).sum();
                return costs;
            }
        };
    }

    private static SolutionCostCalculator getDTPlusNoVehObjectiveFunction(final VehicleRoutingProblem vrp, final double maxCosts, StatisticCollectorForOF statisticCollectorForOF) {
        //if (objectiveFunction != null) return objectiveFunction;

        return new SolutionCostCalculator() {
            @Override
            public double getCosts(VehicleRoutingProblemSolution solution) {
                double costs = MySolutionCostCalculatorFactory.getDefaultCosts(solution, maxCosts);

                statisticCollectorForOF.statsCollector(vrp, solution);
                //add driven time
                costs += TransportCostUtils.getDriveCostRate() * statisticCollectorForOF.getDrivenTimeMap().values().stream().mapToDouble(x -> x).sum();
                costs -= TransportCostUtils.getDriveCostRate() * 3600 * 8 * solution.getRoutes().size();
                //add used number of vehicles
                costs += TransportCostUtils.getVehicleCosts() * solution.getRoutes().size();
                return costs;
            }
        };
    }

    private static SolutionCostCalculator getNoVehObjectiveFunction(final VehicleRoutingProblem vrp, final double maxCosts) {
        //if (objectiveFunction != null) return objectiveFunction;

        return new SolutionCostCalculator() {
            @Override
            public double getCosts(VehicleRoutingProblemSolution solution) {
                double costs = MySolutionCostCalculatorFactory.getDefaultCosts(solution, maxCosts);

                //add used number of vehicles
                costs += TransportCostUtils.getVehicleCosts() * solution.getRoutes().size();
                return costs;
            }
        };
    }

    private static SolutionCostCalculator getTTPlusDDPlusNoVehObjectiveFunction(final VehicleRoutingProblem vrp, final double maxCosts, StatisticCollectorForOF statisticCollectorForOF) {
        //if (objectiveFunction != null) return objectiveFunction;

        return new SolutionCostCalculator() {
            @Override
            public double getCosts(VehicleRoutingProblemSolution solution) {
                double costs = MySolutionCostCalculatorFactory.getDefaultCosts(solution, maxCosts);

                statisticCollectorForOF.statsCollector(vrp, solution);
                //add travel time
                costs += TransportCostUtils.getTravelTimeCosts() * statisticCollectorForOF.getTravelTimeMap().values().stream().mapToDouble(x -> x).sum();
                //add driven distance
                costs += TransportCostUtils.getDrivenDistanceCosts() * statisticCollectorForOF.getDrivenDistanceMap().values().stream().mapToDouble(x -> x).sum();
                //add used number of vehicles
                costs += TransportCostUtils.getVehicleCosts() * solution.getRoutes().size();
                return costs;
            }
        };
    }

    private static SolutionCostCalculator getTTPlusDDObjectiveFunction(final VehicleRoutingProblem vrp, final double maxCosts, StatisticCollectorForOF statisticCollectorForOF) {
        //if (objectiveFunction != null) return objectiveFunction;

        return new SolutionCostCalculator() {
            @Override
            public double getCosts(VehicleRoutingProblemSolution solution) {
                double costs = MySolutionCostCalculatorFactory.getDefaultCosts(solution, maxCosts);

                statisticCollectorForOF.statsCollector(vrp, solution);
                //add travel time
                costs += TransportCostUtils.getTravelTimeCosts() * statisticCollectorForOF.getTravelTimeMap().values().stream().mapToDouble(x -> x).sum();
                //add driven distance
                costs += TransportCostUtils.getDrivenDistanceCosts() * statisticCollectorForOF.getDrivenDistanceMap().values().stream().mapToDouble(x -> x).sum();
                return costs;
            }
        };
    }

    private static SolutionCostCalculator getIVTObjectiveFunction(final VehicleRoutingProblem vrp, final double maxCosts, StatisticCollectorForOF statisticCollectorForOF) {
        //if (objectiveFunction != null) return objectiveFunction;

        return new SolutionCostCalculator() {
            @Override
            public double getCosts(VehicleRoutingProblemSolution solution) {
                double costs = MySolutionCostCalculatorFactory.getDefaultCosts(solution, maxCosts);

                statisticCollectorForOF.statsCollector(vrp, solution);
                //add in-vehicle time
                costs += TransportCostUtils.getInVehicleTimeCost() * statisticCollectorForOF.getInVehicleTimeMap().values().stream().mapToDouble(x -> x).sum();
                return costs;
            }
        };
    }

    private static SolutionCostCalculator getIVTPlusNoVehObjectiveFunction(final VehicleRoutingProblem vrp, final double maxCosts, StatisticCollectorForOF statisticCollectorForOF) {
        //if (objectiveFunction != null) return objectiveFunction;

        return new SolutionCostCalculator() {
            @Override
            public double getCosts(VehicleRoutingProblemSolution solution) {
                double costs = MySolutionCostCalculatorFactory.getDefaultCosts(solution, maxCosts);

                statisticCollectorForOF.statsCollector(vrp, solution);
                //add in-vehicle time
                costs += TransportCostUtils.getInVehicleTimeCost() * statisticCollectorForOF.getInVehicleTimeMap().values().stream().mapToDouble(x -> x).sum();
                //add used number of vehicles
                costs += TransportCostUtils.getVehicleCosts() * solution.getRoutes().size();
                return costs;
            }
        };
    }

    private static SolutionCostCalculator getIVTPlusDDPlusNoVehObjectiveFunction(final VehicleRoutingProblem vrp, final double maxCosts, StatisticCollectorForOF statisticCollectorForOF) {
        //if (objectiveFunction != null) return objectiveFunction;

        return new SolutionCostCalculator() {
            @Override
            public double getCosts(VehicleRoutingProblemSolution solution) {
                double costs = MySolutionCostCalculatorFactory.getDefaultCosts(solution, maxCosts);

                statisticCollectorForOF.statsCollector(vrp, solution);
                //add in-vehicle time
                costs += TransportCostUtils.getInVehicleTimeCost() * statisticCollectorForOF.getInVehicleTimeMap().values().stream().mapToDouble(x -> x).sum();
                //add driven distance
                costs += TransportCostUtils.getDrivenDistanceCosts() * statisticCollectorForOF.getDrivenDistanceMap().values().stream().mapToDouble(x -> x).sum();
                //add used number of vehicles
                costs += TransportCostUtils.getVehicleCosts() * solution.getRoutes().size();
                return costs;
            }
        };
    }

    private static SolutionCostCalculator getIVTPlusDDObjectiveFunction(final VehicleRoutingProblem vrp, final double maxCosts, StatisticCollectorForOF statisticCollectorForOF) {
        //if (objectiveFunction != null) return objectiveFunction;

        return new SolutionCostCalculator() {
            @Override
            public double getCosts(VehicleRoutingProblemSolution solution) {
                double costs = MySolutionCostCalculatorFactory.getDefaultCosts(solution, maxCosts);

                statisticCollectorForOF.statsCollector(vrp, solution);
                //add in-vehicle time
                costs += TransportCostUtils.getInVehicleTimeCost() * statisticCollectorForOF.getInVehicleTimeMap().values().stream().mapToDouble(x -> x).sum();
                //add driven distance
                costs += TransportCostUtils.getDrivenDistanceCosts() * statisticCollectorForOF.getDrivenDistanceMap().values().stream().mapToDouble(x -> x).sum();
                return costs;
            }
        };
    }

    private static SolutionCostCalculator getLatePickupObjectiveFunction(final VehicleRoutingProblem vrp, final double maxCosts, StatisticCollectorForOF statisticCollectorForOF, double serviceTimeInMatsim) {
        //if (objectiveFunction != null) return objectiveFunction;

        return new SolutionCostCalculator() {
            @Override
            public double getCosts(VehicleRoutingProblemSolution solution) {
                double costs = MySolutionCostCalculatorFactory.getDefaultCosts(solution, maxCosts);

                statisticCollectorForOF.statsCollector(vrp, solution);
                //add penalty for too early pickups
                for (Map.Entry<String, Double> entry : statisticCollectorForOF.getPickupTimeMap().entrySet()) {
                    if(statisticCollectorForOF.getDesiredPickupTimeMap().get(entry.getKey()) > entry.getValue()) {
                        costs += TransportCostUtils.getDriveCostRate() * (statisticCollectorForOF.getDesiredPickupTimeMap().get(entry.getKey()) - entry.getValue());
                        double timeOffset = statisticCollectorForOF.getDesiredDeliveryTimeMap().get(entry.getKey()) - statisticCollectorForOF.getDesiredPickupTimeMap().get(entry.getKey());
                        costs += TransportCostUtils.getStandardActivityDeviationCosts() * (timeOffset /*- statisticCollectorForOF.getInVehicleTimeMap().get(entry.getKey())*/) - TransportCostUtils.getInVehicleTimeCost() * (/*statisticCollectorForOF.getInVehicleTimeMap().get(entry.getKey()) +*/ 2 * serviceTimeInMatsim);
                    }else{
                        double timeOffset = statisticCollectorForOF.getDesiredDeliveryTimeMap().get(entry.getKey()) - entry.getValue();
                        costs += TransportCostUtils.getStandardActivityDeviationCosts() * (timeOffset /*- statisticCollectorForOF.getInVehicleTimeMap().get(entry.getKey())*/) - TransportCostUtils.getInVehicleTimeCost() * (/*statisticCollectorForOF.getInVehicleTimeMap().get(entry.getKey()) +*/ 2 * serviceTimeInMatsim);
                    }
                }
                return costs;
            }
        };
    }

    private static SolutionCostCalculator getLatePickupPlusNoVehObjectiveFunction(final VehicleRoutingProblem vrp, final double maxCosts, StatisticCollectorForOF statisticCollectorForOF, double serviceTimeInMatsim) {
        //if (objectiveFunction != null) return objectiveFunction;

        return new SolutionCostCalculator() {
            @Override
            public double getCosts(VehicleRoutingProblemSolution solution) {
                double costs = MySolutionCostCalculatorFactory.getDefaultCosts(solution, maxCosts);

                statisticCollectorForOF.statsCollector(vrp, solution);
                //add penalty for too early pickups
                for (Map.Entry<String, Double> entry : statisticCollectorForOF.getPickupTimeMap().entrySet()) {
                    if(statisticCollectorForOF.getDesiredPickupTimeMap().get(entry.getKey()) > entry.getValue()) {
                        costs += TransportCostUtils.getDriveCostRate() * (statisticCollectorForOF.getDesiredPickupTimeMap().get(entry.getKey()) - entry.getValue());
                        double timeOffset = statisticCollectorForOF.getDesiredDeliveryTimeMap().get(entry.getKey()) - statisticCollectorForOF.getDesiredPickupTimeMap().get(entry.getKey());
                        costs += TransportCostUtils.getStandardActivityDeviationCosts() * (timeOffset /*- statisticCollectorForOF.getInVehicleTimeMap().get(entry.getKey())*/) - TransportCostUtils.getInVehicleTimeCost() * (/*statisticCollectorForOF.getInVehicleTimeMap().get(entry.getKey()) +*/ 2 * serviceTimeInMatsim);
                    }else{
                        double timeOffset = statisticCollectorForOF.getDesiredDeliveryTimeMap().get(entry.getKey()) - entry.getValue();
                        costs += TransportCostUtils.getStandardActivityDeviationCosts() * (timeOffset /*- statisticCollectorForOF.getInVehicleTimeMap().get(entry.getKey())*/) - TransportCostUtils.getInVehicleTimeCost() * (/*statisticCollectorForOF.getInVehicleTimeMap().get(entry.getKey()) +*/ 2 * serviceTimeInMatsim);
                    }
                }
                //add used number of vehicles
                costs += TransportCostUtils.getVehicleCosts() * solution.getRoutes().size();
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
            costs += maxCosts * 2 * (11 - j.getPriority()) + TransportCostUtils.getVehicleCosts();
        }
        return costs;
    }
}
