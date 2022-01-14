package org.matsim.project.prebookingStudy.jsprit.prepare;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.matsim.contrib.dvrp.fleet.*;
import org.matsim.core.network.io.MatsimNetworkReader;
import org.matsim.core.utils.geometry.geotools.MGC;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.api.core.v01.population.Population;

import org.apache.log4j.Logger;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.population.io.PopulationReader;
import org.matsim.core.population.io.PopulationWriter;
import org.matsim.core.scenario.ScenarioUtils;

import java.util.ArrayList;
import java.util.List;

public class ScenarioReducer {

    //BoundingBox:
    private static final double xMin = 452818.;
    private static final double yMin = 5734070.;
    private static final double xMax = 455682.;
    private static final double yMax = 5735598.;

    private static final Logger LOG = Logger.getLogger(ScenarioReducer.class);

    public static void main(String[] args) {
        Geometry boundingBox = createBoundingBox();

        Scenario scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        new MatsimNetworkReader(scenario.getNetwork()).readFile("scenarios/cottbus/network.xml.gz");
        new PopulationReader(scenario).readFile("scenarios/cottbus/drt-test-plans.xml.gz");
        String populationOutputFile = "../test/input/drt-test-plans.xml.gz";
        final FleetSpecification dvrpFleetSpecification = new FleetSpecificationImpl();
        new FleetReader(dvrpFleetSpecification).readFile("scenarios/cottbus/drt-vehicles/500-taxi-capacity-8.xml");
        String dvrpFleetOutputFile = "../test/input/500-taxi-capacity-8.xml.gz";

        ScenarioReducer scenarioReducer = new ScenarioReducer();
        scenarioReducer.scenarioReducer(boundingBox, scenario, populationOutputFile, dvrpFleetSpecification, dvrpFleetOutputFile);
    }

    private static Geometry createBoundingBox() {
        return new GeometryFactory().createPolygon(new Coordinate[]{
                new Coordinate(xMin, yMin), new Coordinate(xMax, yMin),
                new Coordinate(xMax, yMax), new Coordinate(xMin, yMax),
                new Coordinate(xMin, yMin)
        });
    }

    void scenarioReducer(Geometry boundingBox, Scenario scenario, String populationOutputFile, FleetSpecification dvrpFleetSpecification, String dvrpFleetOutputFile){
        List<Person> personToRemoveList = new ArrayList<>();
        List<DvrpVehicleSpecification> vehicles = new ArrayList<>();

        Population population = scenario.getPopulation();
        for (Person personInNewPopulationFile : population.getPersons().values()) {
            for (PlanElement planElementInNewPopulationFile : personInNewPopulationFile.getSelectedPlan().getPlanElements()) {
                if(planElementInNewPopulationFile instanceof Activity){
                    if(("dummy").equals(((Activity)planElementInNewPopulationFile).getType())){

                        //add persons whose activities are not all in boundingBox into List for removing
                        for (Person person : scenario.getPopulation().getPersons().values()) {
                            boolean ifAllActivityInBoundingBox = false;
                            for (PlanElement planElement : person.getSelectedPlan().getPlanElements()) {
                                if (planElement instanceof Activity) {
                                    if(!InBound(boundingBox, ((Activity)planElement).getCoord().getX(), ((Activity)planElement).getCoord().getY())){
                                        ifAllActivityInBoundingBox = false;
                                        break;
                                    } else {
                                        ifAllActivityInBoundingBox = true;
                                    }
                                }
                            }
                            if(!ifAllActivityInBoundingBox){personToRemoveList.add(person);}
                        }

                        //add only the vehicles whose startLink is in boundingBox to List for exporting the new Fleet later
                        for (DvrpVehicleSpecification dvrpVehicleSpecification : dvrpFleetSpecification.getVehicleSpecifications().values()) {
                            if(InBound(boundingBox, scenario.getNetwork().getLinks().get(dvrpVehicleSpecification.getStartLinkId()))){
                                vehicles.add(ImmutableDvrpVehicleSpecification.newBuilder().id(dvrpVehicleSpecification.getId())
                                        .startLinkId(dvrpVehicleSpecification.getStartLinkId())
                                        .capacity(dvrpVehicleSpecification.getCapacity())
                                        .serviceBeginTime(dvrpVehicleSpecification.getServiceBeginTime())
                                        .serviceEndTime(dvrpVehicleSpecification.getServiceEndTime())
                                        .build());
                            }
                        }

                    } else {
                        //The first planElement of first agent's selected plan is activity, but not a 'dummy' activity.
                        throw new RuntimeException("This method is not implemented yet!");
                    }
                    break;
                } else {
                    throw new RuntimeException("The first planElement of first agent's selected plan is not activity.");
                }
            }
            break;
        }
        for (Person person : personToRemoveList) {
            scenario.getPopulation().getPersons().values().remove(person);
        }
        new PopulationWriter(scenario.getPopulation()).write(populationOutputFile);
        //new FleetWriter(dvrpFleetSpecification.getVehicleSpecifications().values().stream()).write(dvrpFleetOutputFile);
        new FleetWriter(vehicles.stream()).write(dvrpFleetOutputFile);
    }

    private boolean InBound(Geometry boundingBox, Link link) {
        Point linkCenterAsPoint = MGC.xy2Point(link.getCoord().getX(), link.getCoord().getY());
        return boundingBox.contains(linkCenterAsPoint);
    }
    private boolean InBound(Geometry boundingBox, double x, double y) {
        Point linkCenterAsPoint = MGC.xy2Point(x, y);
        return boundingBox.contains(linkCenterAsPoint);
    }

}
