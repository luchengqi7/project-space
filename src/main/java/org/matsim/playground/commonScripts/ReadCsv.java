package org.matsim.playground.commonScripts;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class ReadCsv {

    public static void main(String[] args) throws IOException {
        Path inputPlansPath = Path.of("xxx");
        try (
                CSVParser parser = new CSVParser(Files.newBufferedReader(inputPlansPath),
                        CSVFormat.DEFAULT.withDelimiter(';').withFirstRecordAsHeader())) {
            for (CSVRecord record : parser.getRecords()) {
                String data1 = record.get("data_name"); //get by data title (defined by the first row)
                String data2 = record.get(0); // get by index
                System.out.println(data1);
                System.out.println(data2);
            }
        }
    }

}
