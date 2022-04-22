package org.matsim.playground.testingScript;

public class HelloWorld {
    public static void main(String[] args) {
        int x = 0;
        int xMax = 10;
        while (x <= xMax) {
            System.out.println("x = " + x);
            if (x == 5) {
                xMax = 7;
            }
            x++;
        }
    }
}
