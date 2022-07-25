/*
 * *********************************************************************** *
 * project: org.matsim.*
 * *********************************************************************** *
 *                                                                         *
 * copyright       : (C) 2022 by the members listed in the COPYING,        *
 *                   LICENSE and WARRANTY file.                            *
 * email           : info at matsim dot org                                *
 *                                                                         *
 * *********************************************************************** *
 *                                                                         *
 *   This program is free software; you can redistribute it and/or modify  *
 *   it under the terms of the GNU General Public License as published by  *
 *   the Free Software Foundation; either version 2 of the License, or     *
 *   (at your option) any later version.                                   *
 *   See also COPYING, LICENSE and WARRANTY file                           *
 *                                                                         *
 * *********************************************************************** *
 */

package org.matsim.project.drtSchoolTransportStudy.jsprit;

/**
 * @author Michal Maciejewski (michalm), modified by Hao Wu (haowuintub)
 */
public class MyPreplannedSchedulesCalculator {
//	public static class Options {
//		public final boolean infiniteFleet;
//		public final boolean printProgressStatistics;
//		public final int maxIterations;
//		public final boolean multiThread;
//        private final CaseStudyTool caseStudyTool;
//
//        public Options(boolean infiniteFleet, boolean printProgressStatistics, int maxIterations, boolean multiThread, CaseStudyTool caseStudyTool) {
//            this.infiniteFleet = infiniteFleet;
//            this.printProgressStatistics = printProgressStatistics;
//            this.maxIterations = maxIterations;
//            this.multiThread = multiThread;
//            this.caseStudyTool = caseStudyTool;
//        }
//	}
//
//	private final DrtConfigGroup drtCfg;
//	private final FleetSpecification fleetSpecification;
//	private final Network network;
//	private final Population population;
//	private final Options options;
//	private final Config config;
//
//	private final Map<Id<Link>, Location> locationByLinkId = new IdMap<>(Link.class);
//
//	//infinite fleet - set to false when calculating plans inside the mobsim (the fleet is finite)
//	public MyPreplannedSchedulesCalculator(Config config, DrtConfigGroup drtCfg, FleetSpecification fleetSpecification, Network network,
//                                           Population population, Options options) {
//		this.config = config;
//		this.drtCfg = drtCfg;
//		this.fleetSpecification = fleetSpecification;
//		this.network = network;
//		this.population = population;
//		this.options = options;
//	}
//
//	public PreplannedSchedules calculate() {
//		var vrpBuilder = new Builder();
//
//		// create fleet
//		var capacities = fleetSpecification.getVehicleSpecifications()
//				.values()
//				.stream()
//				.map(DvrpVehicleSpecification::getCapacity)
//				.collect(Collectors.toSet());
//		Preconditions.checkState(capacities.size() == 1);
//		var vehicleCapacity = capacities.iterator().next();
//		var vehicleType = VehicleTypeImpl.Builder.newInstance(drtCfg.getMode() + "-vehicle")
//				.addCapacityDimension(0, vehicleCapacity)
//				.build();
//
//		var dvrpVehicles = fleetSpecification.getVehicleSpecifications().values().stream();
//		if (options.infiniteFleet) {
//			dvrpVehicles = StreamEx.of(dvrpVehicles).distinct(DvrpVehicleSpecification::getStartLinkId);
//		}
//
//		dvrpVehicles.forEach(dvrpVehicle -> {
//			var startLinkId = dvrpVehicle.getStartLinkId();
//			var startLink = network.getLinks().get(startLinkId);
//			var startLocation = collectLocationIfAbsent(startLink);
//			var vehicleBuilder = VehicleImpl.Builder.newInstance(dvrpVehicle.getId() + "");
//			vehicleBuilder.setStartLocation(startLocation);
//			vehicleBuilder.setEarliestStart(dvrpVehicle.getServiceBeginTime());
//			vehicleBuilder.setLatestArrival(dvrpVehicle.getServiceEndTime());
//			vehicleBuilder.setType(vehicleType);
//
//			vrpBuilder.addVehicle(vehicleBuilder.build());
//		});
//
//		// collect pickup/dropoff locations
//		for (Person person : population.getPersons().values()) {
//			for (var leg : TripStructureUtils.getLegs(person.getSelectedPlan())) {
//				if (!leg.getMode().equals(drtCfg.getMode())) {
//					continue;
//				}
//
//				var startLink = network.getLinks().get(leg.getRoute().getStartLinkId());
//				collectLocationIfAbsent(startLink);
//
//				var endLink = network.getLinks().get(leg.getRoute().getEndLinkId());
//				collectLocationIfAbsent(endLink);
//			}
//		}
//
//		// compute matrix
//		var vrpCosts = MatrixBasedVrpCosts.calculateVrpCosts(network, locationByLinkId);
//		vrpBuilder.setRoutingCost(vrpCosts);
//
//		var preplannedRequestByShipmentId = new HashMap<String, PreplannedRequest>();
//		// create shipments
//		for (Person person : population.getPersons().values()) {
//			String destinationActivityType = null;
//            assert TripStructureUtils.getTrips(person.getSelectedPlan()).size() == 1;
//			for (TripStructureUtils.Trip trip : TripStructureUtils.getTrips(person.getSelectedPlan())) {
//				destinationActivityType = trip.getDestinationActivity().getType();
//			}
//			for (var leg : TripStructureUtils.getLegs(person.getSelectedPlan())) {
//				if (!leg.getMode().equals(drtCfg.getMode())) {
//					continue;
//				}
//				var startLink = network.getLinks().get(leg.getRoute().getStartLinkId());
//				var pickupLocation = locationByLinkId.get(startLink.getId());
//
//				var endLink = network.getLinks().get(leg.getRoute().getEndLinkId());
//				var dropoffLocation = locationByLinkId.get(endLink.getId());
//
//				double earliestPickupTime = leg.getDepartureTime().seconds();
//				double latestPickupTime = earliestPickupTime + drtCfg.getMaxWaitTime();
//				double travelTime = vrpCosts.getTransportTime(pickupLocation, dropoffLocation, earliestPickupTime, null,
//						null);
//
//				double earliestDeliveryTime = earliestPickupTime + travelTime;
//                double latestDeliveryTime = options.caseStudyTool.identifySchoolStartingTime(destinationActivityType);
//
//				var shipmentId = person.getId()
//						+ "_"
//						+ startLink.getId()
//						+ "_"
//						+ endLink.getId()
//						+ "_"
//						+ earliestPickupTime;
//
//				var shipment = Shipment.Builder.newInstance(shipmentId)
//						.setPickupLocation(pickupLocation)
//						.setDeliveryLocation(dropoffLocation)
//						.setPickupServiceTime(1)
//						.setDeliveryServiceTime(1)
//						.setPickupTimeWindow(new TimeWindow(earliestPickupTime, latestPickupTime))
//						.setDeliveryTimeWindow(new TimeWindow(earliestDeliveryTime, latestDeliveryTime))
//						.addSizeDimension(0, 1)
//						.build();
//				vrpBuilder.addJob(shipment);
//
//				// shipment -> preplanned request
//				var preplannedRequest = new PreplannedRequest(person.getId(), earliestPickupTime, latestPickupTime,
//						latestDeliveryTime, startLink.getId(), endLink.getId());
//				preplannedRequestByShipmentId.put(shipmentId, preplannedRequest);
//			}
//		}
//
//		// run jsprit
//		var problem = vrpBuilder.setFleetSize(options.infiniteFleet ? FleetSize.INFINITE : FleetSize.FINITE).build();
//		// prepare objective function
//		double maxCosts = TransportCostUtils.getRequestRejectionCosts();
//		MySolutionCostCalculatorFactory mySolutionCostCalculatorFactory = new MySolutionCostCalculatorFactory();
//		SolutionCostCalculator objectiveFunction = mySolutionCostCalculatorFactory.getObjectiveFunction(problem, maxCosts, MySolutionCostCalculatorFactory.ObjectiveFunctionType.JspritDefault, config, vrpCosts);
//        String numOfThread = "1";
//        if (options.multiThread) {
//            numOfThread = Integer.toString(Runtime.getRuntime().availableProcessors());
//        }
//		var algorithm = Jsprit.Builder.newInstance(problem)
//				.setObjectiveFunction(new SchoolTrafficObjectiveFunction(problem, options))
//				.setProperty(Jsprit.Parameter.THREADS, numOfThread)
//				.buildAlgorithm();
//		algorithm.setMaxIterations(options.maxIterations);
//		algorithm.getAlgorithmListeners().addListener(new MyIterationEndsListener());
//		var solutions = algorithm.searchSolutions();
//		var bestSolution = Solutions.bestOf(solutions);
//		SolutionPrinter.print(problem, bestSolution, SolutionPrinter.Print.VERBOSE);
//		/*
//		 * not the jsprit default output stats
//		 */
//		String matsimOutputDirectory = config.controler().getOutputDirectory();
//		String jspritStatsDirectory = ((matsimOutputDirectory).endsWith("/")) ? matsimOutputDirectory + "jsprit" : matsimOutputDirectory + "/jsprit";
//		mkdir(jspritStatsDirectory);
//		StatisticCollectorForIterationEndsListener statisticCollectorForIterationEndsListener = new StatisticCollectorForIterationEndsListener(config);
//		statisticCollectorForIterationEndsListener.writeOutputStats(jspritStatsDirectory);
//		StatisticUtils statisticUtils = new StatisticUtils(config, vrpCosts, 1);
//		//statisticUtils.writeConfig(jspritStatsDirectory);
//		//print results to csv files
//		statisticUtils.statsCollector(problem, bestSolution);
//		statisticUtils.writeOutputTrips(jspritStatsDirectory);
//		//statisticUtils.writeCustomerStats(jspritStatsDirectory);
//		//statisticUtils.writeVehicleStats(jspritStatsDirectory, problem, bestSolution);
//		statisticUtils.writeSummaryStats(jspritStatsDirectory, problem, bestSolution);
//
//		Map<PreplannedRequest, Id<DvrpVehicle>> preplannedRequestToVehicle = new HashMap<>();
//		Map<Id<DvrpVehicle>, Queue<PreplannedStop>> vehicleToPreplannedStops = problem.getVehicles()
//				.stream()
//				.collect(Collectors.toMap(v -> Id.create(v.getId(), DvrpVehicle.class), v -> new LinkedList<>()));
//
//		for (var route : bestSolution.getRoutes()) {
//			var vehicleId = Id.create(route.getVehicle().getId(), DvrpVehicle.class);
//			for (var activity : route.getActivities()) {
//				var preplannedRequest = preplannedRequestByShipmentId.get(((JobActivity)activity).getJob().getId());
//
//				boolean isPickup = activity instanceof PickupShipment;
//				if (isPickup) {
//					preplannedRequestToVehicle.put(preplannedRequest, vehicleId);
//				}
//
//				//act -> preplanned stop
//				var preplannedStop = new PreplannedStop(preplannedRequest, isPickup);
//				vehicleToPreplannedStops.get(vehicleId).add(preplannedStop);
//			}
//		}
//
//		var unassignedRequests = bestSolution.getUnassignedJobs()
//				.stream()
//				.map(job -> preplannedRequestByShipmentId.get(job.getId()))
//				.collect(Collectors.toSet());
//
//		return new PreplannedSchedules(preplannedRequestToVehicle, vehicleToPreplannedStops, unassignedRequests);
//
//	}
//
//	private void mkdir(String jspritStatsDirectory) {
//		File dir = new File(jspritStatsDirectory);
//		// if the directory does not exist, create it
//		if (!dir.exists()) {
//			System.out.println("creating directory " + jspritStatsDirectory);
//			boolean result = dir.mkdir();
//			if (result) System.out.println(jspritStatsDirectory + " created");
//		}
//	}
//
//	private Location collectLocationIfAbsent(Link link) {
//		return locationByLinkId.computeIfAbsent(link.getId(), linkId -> Location.Builder.newInstance()
//				.setId(link.getId() + "")
//				.setIndex(locationByLinkId.size())
//				.setCoordinate(Coordinate.newInstance(link.getCoord().getX(), link.getCoord().getY()))
//				.build());
//	}
//
//	private static class SchoolTrafficObjectiveFunction implements SolutionCostCalculator {
//		private final double unassignedPenalty = 10000; // Most important objective
//		private final double costPerVehicle = 200; // Second most important objective
//		private final double drivingCostPerHour = 6.0; // Less important objective
//		private final VehicleRoutingProblem problem;
//		private final Options options;
//
//		SchoolTrafficObjectiveFunction(VehicleRoutingProblem problem, Options options) {
//			this.problem = problem;
//			this.options = options;
//		}
//
//		@Override
//		public double getCosts(VehicleRoutingProblemSolution solution) {
//			double numUnassignedJobs = solution.getUnassignedJobs().size();
//			double costForUnassignedRequests = numUnassignedJobs * unassignedPenalty;
//
//			double numVehiclesUsed = solution.getRoutes().size();
//			double costForFleet = numVehiclesUsed * costPerVehicle;
//			if (!options.infiniteFleet) {
//				costForFleet = 0;
//			}
//
//			VehicleRoutingTransportCosts costMatrix = problem.getTransportCosts();
//			double totalTransportCost = 0;
//			for (VehicleRoute route : solution.getRoutes()) {
//				TourActivity prevAct = route.getStart();
//				for (TourActivity activity : route.getActivities()) {
//					totalTransportCost += costMatrix.getTransportCost(prevAct.getLocation(), activity.getLocation(),
//							prevAct.getEndTime(), route.getDriver(), route.getVehicle());
//					prevAct = activity;
//				}
//			}
//
//			totalTransportCost = totalTransportCost / 3600
//					* drivingCostPerHour;  // In current setup, transport cost = driving time
//			double totalCost = costForUnassignedRequests + costForFleet + totalTransportCost;
//			if (options.printProgressStatistics) {
//				System.out.println("Number of unassigned jobs: " + numUnassignedJobs);
//				System.out.println("Number of vehicles used: " + numVehiclesUsed);
//				System.out.println("Transport cost of the whole fleet: " + totalTransportCost);
//				System.out.println("Total cost = " + totalCost);
//			}
//			return totalCost;
//		}
//	}
}
