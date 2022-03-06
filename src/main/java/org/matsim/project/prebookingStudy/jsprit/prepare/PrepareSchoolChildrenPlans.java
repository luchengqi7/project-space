package org.matsim.project.prebookingStudy.jsprit.prepare;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.locationtech.jts.geom.Geometry;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.population.*;
import org.matsim.application.MATSimAppCommand;
import org.matsim.application.options.ShpOptions;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.population.PersonUtils;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.utils.geometry.CoordUtils;
import org.matsim.core.utils.geometry.geotools.MGC;
import org.matsim.core.utils.gis.ShapeFileReader;
import org.matsim.facilities.ActivityFacilities;
import org.matsim.facilities.ActivityFacility;
import org.opengis.feature.simple.SimpleFeature;
import picocli.CommandLine;

import java.io.FileWriter;
import java.util.*;

@CommandLine.Command(
        name = "population",
        description = "Prepare school children population"
)
public class PrepareSchoolChildrenPlans implements MATSimAppCommand {
    @CommandLine.Option(names = "--input", description = "Path to raw population file", required = true)
    private String input;

    @CommandLine.Option(names = "--attributes", description = "Path to attributes file of population", defaultValue = "")
    private String attributePath;

    @CommandLine.Option(names = "--facility", description = "Path to facility", defaultValue = "")
    private String facilityPath;

    @CommandLine.Option(names = "--residential-area", description = "Path to residential area shape file", defaultValue = "")
    private String residentialAreaPath;

    @CommandLine.Option(names = "--output-folder", description = "Path to output population", required = true)
    private String outputFolder;

    @CommandLine.Mixin
    private ShpOptions shp = new ShpOptions();

    private static final double ageLowerBound = 6;
    private static final double ageUpperBound = 18;
    private static final double schoolTripTimeWindowFrom = 5 * 3600;
    private static final double schoolTripTimeWindowTo = 9 * 3600;

    static final String EDUC_PRIMARY = "educ_primary";
    static final String EDUC_SECONDARY = "educ_secondary";
    static final String EDUC_TERTIARY = "educ_tertiary";
    static final String UNKNOWN = "educ_unknown";

    private final static double CELL_SIZE = 300;  // The cell size for the SNZ data is 300 meters

    private final Random rnd = new Random(1234);

    public static void main(String[] args) {
        new PrepareSchoolChildrenPlans().execute(args);
    }

    @Override
    public Integer call() throws Exception {
        Config config = ConfigUtils.createConfig();
        config.plans().setInputFile(input);
        config.facilities().setInputFile(facilityPath);
        if (!attributePath.equals("")) {
            config.plans().setInputPersonAttributeFile(attributePath);
            config.plans().setInsistingOnUsingDeprecatedPersonAttributeFile(true);
        }

        Scenario scenario = ScenarioUtils.loadScenario(config);
        Population population = scenario.getPopulation();
        Map<String, List<Coord>> schoolData = readSchoolFacility(scenario.getActivityFacilities());

        Geometry studyArea = null;
        if (shp.getShapeFile() != null) {
            studyArea = shp.getGeometry();
        }

        List<Geometry> residentialAreas = new ArrayList<>();
        if (!residentialAreaPath.equals("")) {
            for (SimpleFeature feature : ShapeFileReader.getAllFeatures(residentialAreaPath)) {
                Geometry subArea = (Geometry) feature.getDefaultGeometry();
                if (subArea.isValid()) {
                    residentialAreas.add(subArea);
                }
            }
        }

        Population outputPopulation = PopulationUtils.createPopulation(ConfigUtils.createConfig());
        PopulationFactory populationFactory = PopulationUtils.getFactory();
        CSVPrinter csvWriter = new CSVPrinter(new FileWriter(outputFolder + "/school-trips-data.tsv"), CSVFormat.TDF);
        csvWriter.printRecord("person_id", "departure_time", "from_act", "from_x", "from_y", "to_act", "to_x", "to_y");

        for (Person person : population.getPersons().values()) {
            Object ageValue = person.getAttributes().getAttribute("microm:modeled:age");
            if (ageValue != null) {
                int age = (int) ageValue;
                if (age <= ageUpperBound && age >= ageLowerBound) {
                    Activity homeActivity = (Activity) person.getSelectedPlan().getPlanElements().get(0);
                    if (!homeActivity.getType().equals("home")) {
                        System.err.println("Person" + person.getId().toString() + " does not start with home activity! Manual check is required");
                    }

                    if (!MGC.coord2Point(homeActivity.getCoord()).within(studyArea)) {
                        continue;
                    }

                    Activity identifiedEducationActivity = null;
                    for (PlanElement pE : person.getSelectedPlan().getPlanElements()) {
                        if (pE instanceof Activity) {
                            // According to manual check, those are all feasible activities for education
                            if (((Activity) pE).getType().startsWith("educ_") || ((Activity) pE).getType().equals("errands") || ((Activity) pE).getType().equals("visit")) {
                                if (((Activity) pE).getStartTime().orElse(86400) <= schoolTripTimeWindowTo &&
                                        ((Activity) pE).getStartTime().orElse(0) >= schoolTripTimeWindowFrom)
                                    identifiedEducationActivity = (Activity) pE;
                                // Some person has more than 1 "education" activity. The later one (within the school time window) is usually the correct one
                            }
                        }
                    }
                    if (identifiedEducationActivity == null || !MGC.coord2Point(identifiedEducationActivity.getCoord()).within(studyArea)) {
                        continue;
                    }

                    // modify education activity
                    modifyEducationActivity(identifiedEducationActivity, schoolData, age);
                    modifyHomeActivity(homeActivity, residentialAreas);

                    String personId = person.getId().toString() + "_school_trip";
                    Person outputPerson = populationFactory.createPerson(Id.createPersonId(personId));
                    Plan plan = populationFactory.createPlan();
                    plan.addActivity(homeActivity);
                    plan.addLeg(populationFactory.createLeg(TransportMode.drt));
                    plan.addActivity(identifiedEducationActivity);
                    outputPerson.addPlan(plan);
                    PersonUtils.setAge(outputPerson, age);
                    outputPopulation.addPerson(outputPerson);
                    csvWriter.printRecord(personId, homeActivity.getEndTime().orElse(86400),
                            homeActivity.getType(), homeActivity.getCoord().getX(), homeActivity.getCoord().getY(),
                            identifiedEducationActivity.getType(), identifiedEducationActivity.getCoord().getX(), identifiedEducationActivity.getCoord().getY());
                }
            }
        }

        csvWriter.close();
        PopulationWriter populationWriter = new PopulationWriter(outputPopulation);
        populationWriter.write(outputFolder + "/school-trips.plans.xml.gz");

        return 0;
    }

    /**
     * Diffuse the coordinate of the home activity. We try to make them land in the residential area (when provided).
     * But if we can't find any points for 100 trials, then we just use the diffused coord.
     */
    private void modifyHomeActivity(Activity homeActivity, List<Geometry> residentialAreas) {
        Coord originalCoord = homeActivity.getCoord();
        if (residentialAreas.size() == 0) {
            Coord diffusedCoord = diffuseCoord(originalCoord);
            homeActivity.setCoord(diffusedCoord);
        } else {
            int counter = 0;

            boolean locationFound = false;
            while (!locationFound) {
                Coord diffusedCoord = diffuseCoord(originalCoord);
                if (counter >= 200) {
                    System.err.println("No suitable coordinate found in the residential area. Will simply use the diffused coord");
                    homeActivity.setCoord(diffusedCoord);
                    break;
                }
                for (Geometry subArea : residentialAreas) {
                    if (MGC.coord2Point(diffusedCoord).within(subArea)) {
                        homeActivity.setCoord(diffusedCoord);
                        locationFound = true;
                        break;
                    }
                }
                counter++;
            }
        }
    }

    private void modifyEducationActivity(Activity identifiedEducationActivity, Map<String, List<Coord>> schoolData, int age) {
        identifiedEducationActivity.setEndTime(46800);  //13:00:00 This value doesn't really matter. Just set them to the same value in order to remove some strange values from raw data
        Coord originalCoord = identifiedEducationActivity.getCoord();
        Coord diffusedCoord = diffuseCoord(originalCoord);

        String educType = identifiedEducationActivity.getType();
        if (!schoolData.containsKey(educType)) {
            identifiedEducationActivity.setType(UNKNOWN);
            if (age <= 11) {
                identifiedEducationActivity.setType(EDUC_PRIMARY);
            }
        }

        List<Coord> potentialCoords = schoolData.get(identifiedEducationActivity.getType());
        Coord schoolCoord = null;
        double minDistance = Double.MAX_VALUE;
        for (Coord potentialSchoolCoord : potentialCoords) {
            double distance = CoordUtils.calcEuclideanDistance(diffusedCoord, potentialSchoolCoord);
            if (distance < minDistance) {
                minDistance = distance;
                schoolCoord = potentialSchoolCoord;
            }
        }
        identifiedEducationActivity.setCoord(schoolCoord);
    }

    private Map<String, List<Coord>> readSchoolFacility(ActivityFacilities activityFacilities) {
        Map<String, List<Coord>> schoolData = new HashMap<>();
        schoolData.put(EDUC_PRIMARY, new ArrayList<>());
        schoolData.put(EDUC_SECONDARY, new ArrayList<>());
        schoolData.put(EDUC_TERTIARY, new ArrayList<>());
        schoolData.put(UNKNOWN, new ArrayList<>()); // all school facilities are included here

        for (ActivityFacility facility : activityFacilities.getFacilities().values()) {
            String schoolName = facility.getAttributes().getAttribute(PrepareSchoolFacility.SCHOOL_NAME).toString();
            boolean facilityAssigned = false;
            if (schoolName.contains("Grund")) {
                schoolData.get(EDUC_PRIMARY).add(facility.getCoord());
                facilityAssigned = true;
            } else {
                schoolData.get(UNKNOWN).add(facility.getCoord());  // will be used for children with age 11 - 18, with other activity type
            }

            if (schoolName.contains("Real") || schoolName.contains("Gym")) {
                schoolData.get(EDUC_SECONDARY).add(facility.getCoord());
                facilityAssigned = true;
            }

            if (!facilityAssigned) {
                schoolData.get(EDUC_TERTIARY).add(facility.getCoord());  // The rest of the educational facilities belongs to Tertiary
            }
        }
        return schoolData;
    }

    /**
     * Diffuse the coordinate from the grid into the map based on 2 independent gaussian distributions.
     */
    private Coord diffuseCoord(Coord inputCoord) {
        double x = inputCoord.getX() + rnd.nextGaussian() * PrepareSchoolChildrenPlans.CELL_SIZE;
        double y = inputCoord.getY() + rnd.nextGaussian() * PrepareSchoolChildrenPlans.CELL_SIZE;
        return new Coord(x, y);
    }

}
