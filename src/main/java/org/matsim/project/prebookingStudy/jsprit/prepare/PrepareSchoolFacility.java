package org.matsim.project.prebookingStudy.jsprit.prepare;

import org.geotools.data.shapefile.ShapefileDataStore;
import org.locationtech.jts.geom.Geometry;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.application.options.ShpOptions;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.utils.geometry.geotools.MGC;
import org.matsim.core.utils.gis.ShapeFileReader;
import org.matsim.facilities.*;
import org.opengis.feature.simple.SimpleFeature;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

public class PrepareSchoolFacility {

    static final String SCHOOL_NAME = "school name";

    public static void main(String[] args) throws IOException {
        Network network = NetworkUtils.readNetwork("/Users/luchengqi/Documents/MATSimScenarios/Vulkaneifel/drt-prebooking-study/network.xml.gz");
        Path schoolShp = Path.of("/Users/luchengqi/Documents/MATSimScenarios/Vulkaneifel/drt-prebooking-study/shp/landuse/vulkaneifel-amenity-school.shp");
        String serviceAreaShpPath = "/Users/luchengqi/Documents/MATSimScenarios/Vulkaneifel/drt-prebooking-study/shp/ServiceArea/vulkaneifel.shp";

        ShapefileDataStore ds = ShpOptions.openDataStore(schoolShp);
        ds.setCharset(StandardCharsets.UTF_8);
        List<SimpleFeature> schoolFeatures = ShapeFileReader.getSimpleFeatures(ds);

        List<SimpleFeature> serviceAreaFeatures = (List<SimpleFeature>) ShapeFileReader.getAllFeatures(serviceAreaShpPath);
        assert serviceAreaFeatures.size() == 1;
        Geometry serviceAreaGeometry = (Geometry) serviceAreaFeatures.get(0).getDefaultGeometry();

        ActivityFacilities facilities = new ActivityFacilitiesImpl();
        for (SimpleFeature feature : schoolFeatures) {
            Geometry geometry = (Geometry) feature.getDefaultGeometry();
            Coord coord = MGC.point2Coord(geometry.getCentroid());
            if (!geometry.getCentroid().within(serviceAreaGeometry)) {
                continue;
            }

            // Create facility
            ActivityFacilitiesFactory facilitiesFactory = new ActivityFacilitiesFactoryImpl();

            String facilityIdString = feature.getAttribute("osm_way_id").toString();
            Link link = NetworkUtils.getNearestLink(network, coord);
            ActivityFacility facility = facilitiesFactory.createActivityFacility(Id.create(facilityIdString, ActivityFacility.class), coord, link.getId());
            String schoolName = feature.getAttribute("name").toString();
            if (schoolName.equals("")) {
                schoolName = "unknown";
            }
            schoolName = schoolName.replace("Grunschule", "Grundschule"); // There is a typo in the OSM data
            facility.getAttributes().putAttribute(SCHOOL_NAME, schoolName);

            facilities.addActivityFacility(facility);
            System.out.println("School name =  " + feature.getAttribute("name").toString() + " Mapped linkId = " + link.getId());
        }

        FacilitiesWriter facilitiesWriter = new FacilitiesWriter(facilities);
        facilitiesWriter.write("/Users/luchengqi/Documents/MATSimScenarios/Vulkaneifel/drt-prebooking-study/schools.facility.xml");
    }

}
