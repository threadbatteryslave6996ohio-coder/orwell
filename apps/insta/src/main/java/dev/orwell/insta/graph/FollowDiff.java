package dev.orwell.insta.graph;

/**
 * What one walk changed.
 *
 * @param seen        accounts the walk returned and could be stored
 * @param skippedNoId rows dropped for having no Instagram id — indistinguishable from rows never
 *                    seen, which is why any of them make the walk untrustworthy for retirement
 * @param added       edges that did not exist before
 * @param retired     edges marked lost, always {@code 0} unless the walk was trusted
 * @param retirementSkipped why retirement did not run, or {@code null} if it did
 */
public record FollowDiff(
        int seen, int skippedNoId, int added, int retired, String retirementSkipped) {

    public boolean retirementRan() {
        return retirementSkipped == null;
    }
}
