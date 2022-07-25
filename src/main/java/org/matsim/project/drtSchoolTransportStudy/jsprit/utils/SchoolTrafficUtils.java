package org.matsim.project.drtSchoolTransportStudy.jsprit.utils;

import org.matsim.core.gbl.Gbl;

@Deprecated
public class SchoolTrafficUtils {

    public enum SchoolStartTimeScheme {DEFAULT, UNIFORM, READ_FROM_SCHOOL_ACTIVITY}
    // DEFAULT: Use the travel time alpha and beta to determine the latest arrival time, which will be interpreted as school starting time
    // (This will work for all Door2door case studies, as the departure time is calculated based on the same alpha and beta)
    // UNIFORM: All school starts at 08:00 am (28800)
    // READ_FROM_SCHOOL_ACTIVITY: determine school starting time based on school activity type (format: xxx_starting_at_yyy)

    public static double identifySchoolStartTime(SchoolStartTimeScheme schoolStartTimeScheme, String activityType) {
        switch (schoolStartTimeScheme) {
            case UNIFORM:
                return 28800;
            case READ_FROM_SCHOOL_ACTIVITY:
                return readSchoolStartingTimeFromActivity(activityType);
            default:
                throw new RuntimeException(Gbl.NOT_IMPLEMENTED);
        }
    }

    private static double readSchoolStartingTimeFromActivity(String activityType) {
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
