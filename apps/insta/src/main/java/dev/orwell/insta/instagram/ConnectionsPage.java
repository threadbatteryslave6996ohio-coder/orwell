package dev.orwell.insta.instagram;

import java.util.List;

/**
 * One page of a follower or following list.
 *
 * @param nextCursor the value to pass back as {@code ?cursor=} for the next page, or {@code null}
 *                   when there is no way to ask for more
 * @param endOfList  whether this page is genuinely the end. Not the same as {@code nextCursor ==
 *                   null}: an actor that cannot paginate always returns a null cursor, and one
 *                   that handed back exactly as many accounts as it was asked for has said nothing
 *                   about whether more exist. Only a walk that ends on {@code endOfList} may
 *                   retire an edge.
 */
public record ConnectionsPage(List<InstagramAccount> accounts, String nextCursor, boolean endOfList) {
}
