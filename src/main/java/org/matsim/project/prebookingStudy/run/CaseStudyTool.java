package org.matsim.project.prebookingStudy.run;

import org.matsim.contrib.drt.run.DrtConfigGroup;
import org.matsim.core.config.Config;
import org.matsim.core.gbl.Gbl;

public class CaseStudyTool {
    public enum SchoolStartingTime {UNIFORM, TWO_SCHOOL_STARTING_TIME}

    public static final double UNIFORM_SCHOOL_STARTING_TIME = 28800;

    public enum ServiceScheme {DOOR_TO_DOOR, STOP_BASED, STOP_BASED_ADAPTED}

    private final String alpha;
    private final String beta;
    private final SchoolStartingTime schoolStartingTime;
    private final ServiceScheme serviceScheme;

    public CaseStudyTool(String alpha, String beta, SchoolStartingTime schoolStartingTime, ServiceScheme serviceScheme) {
        this.alpha = alpha;
        this.beta = beta;
        this.schoolStartingTime = schoolStartingTime;
        this.serviceScheme = serviceScheme;
    }

    /**
     * Constructor for the default case study setup
     */
    public CaseStudyTool() {
        this.alpha = "2";
        this.beta = "1200";
        this.schoolStartingTime = SchoolStartingTime.UNIFORM;
        this.serviceScheme = ServiceScheme.DOOR_TO_DOOR;
    }

    public void prepareCaseStudy(Config config, DrtConfigGroup drtConfigGroup) {
        String inputPlansFile = "./case-study-plans/alpha_" + alpha + "-beta_" + beta + ".plans.xml.gz";
        drtConfigGroup.setMaxWaitTime(7200);
        drtConfigGroup.setMaxTravelTimeAlpha(Double.parseDouble(alpha));
        drtConfigGroup.setMaxTravelTimeBeta(Double.parseDouble(beta));

        switch (schoolStartingTime) {
            case UNIFORM:
                break;
            case TWO_SCHOOL_STARTING_TIME:
                inputPlansFile = inputPlansFile.replace(".plans.xml.gz", "-2_starting_time.plans.xml.gz");
                break;
            default:
                throw new RuntimeException("Unknown school starting time setting");
        }

        switch (serviceScheme) {
            case DOOR_TO_DOOR:
                drtConfigGroup.setOperationalScheme(DrtConfigGroup.OperationalScheme.door2door);
                break;
            case STOP_BASED:
                drtConfigGroup.setOperationalScheme(DrtConfigGroup.OperationalScheme.stopbased);
                drtConfigGroup.setTransitStopFile("vulkaneifel-v1.0-drt-stops.xml");
                break;
            case STOP_BASED_ADAPTED:
                drtConfigGroup.setOperationalScheme(DrtConfigGroup.OperationalScheme.stopbased);
                drtConfigGroup.setTransitStopFile("vulkaneifel-v1.0-drt-stops.xml");
                inputPlansFile = inputPlansFile.replace(".plans.xml.gz", "-adapted_to_drt_stops.plans.xml.gz");
                // In the stop based adapted case, departure time is modified based on the DRT stops (most students will depart earlier)
                break;
            default:
                throw new RuntimeException("Unknown service scheme setting");
        }

        config.plans().setInputFile(inputPlansFile);
    }

    public SchoolStartingTime getSchoolStartingTime() {
        return schoolStartingTime;
    }

    public ServiceScheme getServiceScheme() {
        return serviceScheme;
    }

    public String getAlpha() {
        return alpha;
    }

    public String getBeta() {
        return beta;
    }

    public double identifySchoolStartingTime(String schoolActivityType) {
        switch (schoolStartingTime) {
            case UNIFORM:
                return UNIFORM_SCHOOL_STARTING_TIME;
            case TWO_SCHOOL_STARTING_TIME:
                return readSchoolStartingTimeFromActivity(schoolActivityType);
            default:
                throw new RuntimeException(Gbl.NOT_IMPLEMENTED);
        }
    }

    private double readSchoolStartingTimeFromActivity(String activityType) {
        if (activityType.contains("starting_at_")) {
            String[] activityTypeStrings = activityType.split("_");
            int size = activityTypeStrings.length;
            return Double.parseDouble(activityTypeStrings[size - 1]);
        } else {
            throw new RuntimeException("The activity type (name) of the school activity does not include starting time. " +
                    "Please check the input plans or use UNIFORM or DEFAULT school starting scheme");
        }
    }
}
