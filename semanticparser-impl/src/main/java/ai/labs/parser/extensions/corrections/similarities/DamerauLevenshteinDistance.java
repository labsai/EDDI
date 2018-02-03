package ai.labs.parser.extensions.corrections.similarities;

/**
 * Damerau-Levenshtein Distance
 * Based on the algorithms provided at the following websites:
 * <p/>
 * http://snippets.dzone.com/posts/show/6942
 * http://en.wikipedia.org/wiki/Damerau%E2%80%93Levenshtein_distance
 *
 * @author Richard Clayton (Berico Technologies)
 * @date June 25, 2011
 */
public class DamerauLevenshteinDistance implements IDistanceCalculator {
    /**
     * Calculate the Damerau-Levenshtein Distance (edit distance)
     * between two strings.
     *
     * @param source Source input string
     * @param target Target input string
     * @return The number of substitutions it would take
     *         to make the source string identical to the target
     *         string
     */
    public int calculate(String source, String target) {
        //If both strings are empty, I'm of the opinion that
        //this is an error (technically the distance is zero).
        assert (!(source.isEmpty() && target.isEmpty()));

        //If the source string is empty, the distance is the
        //length of the target string.
        if (source.isEmpty()) {
            return target.length();
        }

        //If the target string is empty, the distance is the
        //length of the source string.
        if (target.isEmpty()) {
            return source.length();
        }

        //Delegate the calculation to the method that produces the matrix
        //and distance, but then only return the distance
        return calculateAndReturnFullResult(source, target).getDistance();
    }

    /**
     * Perform the distance calculation, but also return the
     * resulting matrix and distance.
     *
     * @param source Source input string
     * @param target Target input string
     * @return A simple object with the matrix and distance
     */
    public DameauLevenshteinDistanceResult calculateAndReturnFullResult(String source, String target) {
        //If both strings are empty, I'm of the opinion that
        //this is an error (technically the distance is zero).
        assert (!(source.isEmpty() && target.isEmpty()));

        //We are going to construct a matrix of distances
        int[][] distanceMatrix = new int[source.length() + 1][target.length() + 1];

        //We need indexers from 0 to the length of the source string.
        //This sequential set of numbers will be the row "headers"
        //in the matrix.
        for (int sourceIndex = 0;
             sourceIndex <= source.length();
             sourceIndex++) {

            //Set the value of the first cell in the row
            //equivalent to the current value of the iterator
            distanceMatrix[sourceIndex][0] = sourceIndex;
        }

        //We need indexers from 0 to the length of the target string.
        //This sequential set of numbers will be the column "headers"
        //in the matrix.
        for (int targetIndex = 0;
             targetIndex <= target.length();
             targetIndex++) {

            //Set the value of the first cell in the column
            //equivalent to the current value of the iterator
            distanceMatrix[0][targetIndex] = targetIndex;
        }

        //We'll use this to add a penalty
        //to some operations.
        int cost = 0;

        //Iterate over all characters in the source
        //string.
        for (int sourceIndex = 1;
             sourceIndex <= source.length();
             sourceIndex++) {

            //Iterate over all characters in the target
            //string.
            for (int targetIndex = 1;
                 targetIndex <= target.length();
                 targetIndex++) {

                //If the current characters in both strings are equal
                if (source.charAt(sourceIndex - 1) == target.charAt(targetIndex - 1)) {
                    //There is no penalty.
                    cost = 0;
                } else {
                    //Not equal, there is a penalty.
                    cost = 1;
                }

                //We want to find the current distance by determining
                //the shortest path to a match (hence the 'minimum'
                //calculation on distances).
                distanceMatrix[sourceIndex][targetIndex]
                        = minimum(
                        //Character match between current character in
                        //source string and next character in target
                        distanceMatrix[sourceIndex - 1][targetIndex] + 1,
                        //Character match between next character in
                        //source string and current character in target
                        distanceMatrix[sourceIndex][targetIndex - 1] + 1,
                        //No match, at current, add cumulative penalty
                        distanceMatrix[sourceIndex - 1][targetIndex - 1] + cost);

                //We don't want to do the next series of calculations on
                //the first pass because we would get an index out of bounds
                //exception.
                if (sourceIndex == 1 || targetIndex == 1) {
                    continue;
                }

                //transposition check (if the current and previous character are
                //switched around (e.g.: t[se]t and t[es]t)...
                if (source.charAt(sourceIndex - 1) == target.charAt(targetIndex - 2)
                        && source.charAt(sourceIndex - 2) == target.charAt(targetIndex - 1)) {

                    //What's the minimum cost between the current distance
                    //and a transposition.
                    distanceMatrix[sourceIndex][targetIndex]
                            = minimum(
                            //Current cost
                            distanceMatrix[sourceIndex][targetIndex],
                            //Transposition
                            distanceMatrix[sourceIndex - 2][targetIndex - 2] + cost);
                }
            }
        }

        //Return the matrix and distance as the result
        return new DameauLevenshteinDistanceResult(distanceMatrix);
    }

    /**
     * Calculate the minimum value from an array of values.
     *
     * @param values Array of values.
     * @return minimum value of the provided set.
     */
    private static int minimum(int... values) {

        //Hopefully, everything should be smaller
        //than the max int value!
        int currentMinimum = Integer.MAX_VALUE;

        //Iterate over all provided values
        for (int value : values) {

            //Take the minimum value between the current
            //minimum and the current value of the
            //iteration
            currentMinimum = Math.min(value, currentMinimum);
        }

        //return the minimum value.
        return currentMinimum;
    }

    /**
     * Simple container for the result of the Dameau-Levenshtein
     * Distance calculation
     *
     * @author Richard Clayton (Berico Technologies)
     * @date June 25, 2011
     */
    public class DameauLevenshteinDistanceResult {

        //Distance matrix
        private int[][] distanceMatrix;

        /**
         * Instantiate the object with the resulting distance matrix
         *
         * @param distanceMatrix Matrix of distances between edits
         */
        public DameauLevenshteinDistanceResult(int[][] distanceMatrix) {
            this.distanceMatrix = distanceMatrix;
        }

        /**
         * Get the Distance Matrix
         *
         * @return Matrix of edit distances
         */
        public int[][] getDistanceMatrix() {
            return distanceMatrix;
        }

        /**
         * Get the Edit Distance
         *
         * @return number of changes to make before
         *         both strings are identical
         */
        public int getDistance() {
            return
                    distanceMatrix[distanceMatrix.length - 1][distanceMatrix[0].length - 1];
        }

        /**
         * Get a string representation of this class
         *
         * @return A friendly display of the distance and matrix
         */
        @Override
        public String toString() {

            StringBuilder sb = new StringBuilder();

            sb.append(String.format("Distance: %s \n", this.getDistance()));
            sb.append("Matrix: \n\n");

            for (int i = 0; i < this.distanceMatrix.length; i++) {

                sb.append("| ");

                for (int j = 0; j < this.distanceMatrix[0].length; j++) {

                    sb.append(String.format("\t%s", this.distanceMatrix[i][j]));
                }

                sb.append(" |\n");
            }

            return sb.toString();
        }
    }

}
