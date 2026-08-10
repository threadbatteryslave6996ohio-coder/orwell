package dev.orwell.insta.instagram;

import java.util.List;

/**
 * One page of a follower or following list.
 *
 * @param nextCursor the value to pass back as {@code ?cursor=} for the next page, or {@code null}
 *                   when the actor reported no more accounts. Absent means the list is exhausted —
 *                   it does not mean the page was full.
 */
public record ConnectionsPage(List<InstagramAccount> accounts, String nextCursor) {
}
