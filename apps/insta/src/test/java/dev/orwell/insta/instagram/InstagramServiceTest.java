package dev.orwell.insta.instagram;

import com.fasterxml.jackson.databind.JsonNode;
import dev.orwell.insta.InstaJson;
import dev.orwell.insta.apify.ApifyClient;
import dev.orwell.insta.apify.ApifyException;
import dev.orwell.insta.support.ApifyStubServer;
import dev.orwell.insta.support.InMemoryScrapeCache;
import dev.orwell.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What a caller actually gets: which actor is asked what, how its dataset items become values,
 * which failures stay distinguishable from one another, and which requests the cache stops from
 * ever reaching Apify.
 */
class InstagramServiceTest {
    private static final Logger NO_OP_LOGGER = entry -> {
    };
    private static final String PROFILE_ACTOR = "apify/instagram-profile-scraper";

    private ApifyStubServer apify;
    private InMemoryScrapeCache cache;

    @BeforeEach
    void startStub() throws IOException {
        apify = ApifyStubServer.start();
        cache = new InMemoryScrapeCache();
    }

    @AfterEach
    void stopStub() {
        apify.close();
    }

    @Test
    void readsBothCountsOffTheProfileActor() {
        apify.responds(200, """
                [{"id":"12345","username":"nasa","fullName":"NASA","biography":"Explore",
                  "followersCount":97000000,"followsCount":78,"postsCount":3900,
                  "verified":true,"private":false,"profilePicUrl":"https://pic"}]""");

        InstagramProfile profile = service().profile("nasa");

        assertThat(profile.username()).isEqualTo("nasa");
        assertThat(profile.followersCount()).isEqualTo(97_000_000L);
        // The profile actor calls the following count "followsCount"; the API must not repeat that.
        assertThat(profile.followingCount()).isEqualTo(78L);
        assertThat(profile.isVerified()).isTrue();
        assertThat(apify.runStart().path()).contains("apify~instagram-profile-scraper");
    }

    /**
     * The profile actor bundles recent posts into the same dataset item as the counts. They cost
     * nothing extra, so discarding them would be paying for data and throwing it away.
     */
    @Test
    void readsTheRecentPostsBundledIntoTheProfileItem() {
        apify.responds(200, """
                [{"id":"1","username":"nasa","followersCount":5,"latestPosts":[
                  {"id":"p1","shortCode":"abc","caption":"Launch","timestamp":1754870400,
                   "type":"Image","likesCount":1200,"commentsCount":40,
                   "displayUrl":"https://cdn/p1.jpg","url":"https://instagram.com/p/abc"},
                  {"id":"p2","caption":"Orbit","likesCount":7}]}]""");

        InstagramProfile profile = service().profile("nasa");

        assertThat(profile.latestPosts()).extracting(InstagramPost::id).containsExactly("p1", "p2");
        assertThat(profile.latestPosts().get(0).caption()).isEqualTo("Launch");
        assertThat(profile.latestPosts().get(0).likesCount()).isEqualTo(1200L);
        assertThat(profile.latestPosts().get(0).takenAt()).isEqualTo(Instant.ofEpochSecond(1754870400));
    }

    /** A post with no id cannot be keyed, and an actor that returns none is not an error. */
    @Test
    void toleratesMissingOrUnusablePosts() {
        apify.responds(200, """
                [{"id":"1","username":"nasa","latestPosts":[{"caption":"no id here"}]}]""");
        assertThat(service().profile("nasa").latestPosts()).isEmpty();

        apify.responds(200, "[{\"id\":\"1\",\"username\":\"nasa\"}]");
        assertThat(service().profile("nasa").latestPosts()).isEmpty();
    }

    /** A count the actor could not read is absent, not zero — a caller must be able to tell. */
    @Test
    void leavesACountNullWhenTheActorDidNotReportIt() {
        apify.responds(200, "[{\"username\":\"ghost\"}]");

        InstagramProfile profile = service().profile("ghost");

        assertThat(profile.followersCount()).isNull();
        assertThat(profile.followingCount()).isNull();
    }

    @Test
    void reportsAnUnknownProfileAsNotFound() {
        apify.responds(200, "[]");

        assertThatThrownBy(() -> service().profile("nobody"))
                .isInstanceOf(ProfileNotFoundException.class);
    }

    /** A brand new or briefly unreachable account must not stay "missing" for a whole day. */
    @Test
    void doesNotCacheAProfileThatWasNotFound() {
        apify.responds(200, "[]");
        assertThatThrownBy(() -> service().profile("nobody"))
                .isInstanceOf(ProfileNotFoundException.class);

        assertThat(cache.entries()).isEmpty();
    }

    @Test
    void asksTheConnectionsActorForFollowersAndMapsThem() {
        apify.responds(200, """
                [{"id":"1","username":"alice","full_name":"Alice","is_private":false,
                  "is_verified":true,"profile_pic_url":"https://a"},
                 {"id":"2","username":"bob","full_name":"Bob","is_private":true,"is_verified":false}]""");

        List<InstagramAccount> followers =
                service().connections("nasa", ConnectionType.FOLLOWERS, 200, null).accounts();

        assertThat(followers).extracting(InstagramAccount::username).containsExactly("alice", "bob");
        assertThat(followers.get(0).fullName()).isEqualTo("Alice");
        assertThat(followers.get(0).isVerified()).isTrue();
        assertThat(followers.get(1).isPrivate()).isTrue();

        JsonNode input = actorInput();
        assertThat(apify.runStart().path())
                .contains("scraping_solutions~instagram-scraper-followers-following-no-cookies");
        assertThat(input.get("Account").get(0).asText()).isEqualTo("nasa");
        assertThat(input.get("dataToScrape").asText()).isEqualTo("Followers");
        assertThat(input.get("resultsLimit").asInt()).isEqualTo(200);
    }

    @Test
    void asksTheSameActorForFollowingWithTheOtherScrapeType() {
        apify.responds(200, "[{\"username\":\"alice\"}]");

        service().connections("nasa", ConnectionType.FOLLOWING, 60, null);

        assertThat(actorInput().get("dataToScrape").asText()).isEqualTo("Following");
    }

    /**
     * The connections actor refuses a {@code resultsLimit} under 50, so a smaller request is
     * floored for the actor and enforced by the run's own {@code maxItems} instead.
     */
    @Test
    void floorsTheActorLimitButStillReturnsOnlyWhatWasAsked() {
        apify.responds(200, "[{\"username\":\"a\"},{\"username\":\"b\"},{\"username\":\"c\"}]");

        ConnectionsPage page = service().connections("nasa", ConnectionType.FOLLOWERS, 2, null);

        assertThat(actorInput().get("resultsLimit").asInt()).isEqualTo(50);
        assertThat(apify.runStart().query()).contains("maxItems=2");
        assertThat(page.accounts()).hasSize(2);
    }

    /** Every returned account is billed, so the ceiling has to hold whatever a caller asks for. */
    @Test
    void clampsAnOversizedLimitToTheConfiguredCeiling() {
        apify.responds(200, "[]");

        service().connections("nasa", ConnectionType.FOLLOWERS, 999_999, null);

        assertThat(apify.runStart().query()).contains("maxItems=500");
    }

    @Test
    void fallsBackToTheDefaultLimitWhenNoneIsGiven() {
        apify.responds(200, "[]");

        service().connections("nasa", ConnectionType.FOLLOWERS, null, null);

        assertThat(apify.runStart().query()).contains("maxItems=100");
    }

    /** A private or empty account is an honest empty list, not an error. */
    @Test
    void returnsAnEmptyListWhenTheActorSeesNothing() {
        apify.responds(200, "[]");

        assertThat(service().connections("private.account", ConnectionType.FOLLOWERS, 50, null)
                .accounts()).isEmpty();
    }

    /** A row with no username cannot be identified, so it is dropped rather than half-mapped. */
    @Test
    void skipsDatasetItemsWithNoUsername() {
        apify.responds(200, "[{\"username\":\"alice\"},{\"full_name\":\"No Handle\"},{\"username\":\"\"}]");

        assertThat(service().connections("nasa", ConnectionType.FOLLOWERS, 50, null).accounts())
                .extracting(InstagramAccount::username).containsExactly("alice");
    }

    @Test
    void acceptsAPastedHandleAndIsCaseInsensitive() {
        apify.responds(200, "[{\"username\":\"nasa\",\"followersCount\":1}]");

        assertThat(service().profile("  @NASA  ").username()).isEqualTo("nasa");
        assertThat(actorInput().get("usernames").get(0).asText()).isEqualTo("nasa");
    }

    /** A username we would not send is rejected before it can reach a third-party actor. */
    @Test
    void rejectsAnImpossibleUsernameWithoutSpendingARun() {
        assertThatThrownBy(() -> service().profile("not a/username"))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(apify.requests()).isEmpty();
    }

    // ---------------------------------------------------------------- paging

    /** The cursor is what tells a caller there is more; a full page on its own proves nothing. */
    @Test
    void handsBackACursorWhenTheActorSaysMoreAccountsRemain() {
        apify.responds(200, "[{\"username\":\"alice\"}]");
        apify.outputs("{\"continuations\":[{\"nextContinuationToken\":\"TOKEN-2\"}]}");

        ConnectionsPage page = service().connections("nasa", ConnectionType.FOLLOWERS, 50, null);

        assertThat(page.nextCursor()).isNotNull();
    }

    @Test
    void handsBackNoCursorWhenTheRunWroteNoOutputRecord() {
        apify.responds(200, "[{\"username\":\"alice\"}]");

        assertThat(service().connections("nasa", ConnectionType.FOLLOWERS, 50, null).nextCursor())
                .isNull();
    }

    @Test
    void sendsTheCursorBackToTheActorAsAContinuationToken() {
        apify.responds(200, "[{\"username\":\"alice\"}]");
        apify.outputs("{\"continuations\":[{\"nextContinuationToken\":\"TOKEN-2\"}]}");
        String cursor = service().connections("nasa", ConnectionType.FOLLOWERS, 50, null).nextCursor();

        service().connections("nasa", ConnectionType.FOLLOWERS, 50, cursor);

        assertThat(actorInput().get("continuationToken").asText()).isEqualTo("TOKEN-2");
    }

    /**
     * The actor's own rule is that a token belongs to one account and one direction. Replaying one
     * elsewhere is undefined behaviour upstream, so it is refused here instead.
     */
    @Test
    void refusesACursorFromADifferentListOrAccount() {
        apify.responds(200, "[{\"username\":\"alice\"}]");
        apify.outputs("{\"continuations\":[{\"nextContinuationToken\":\"TOKEN-2\"}]}");
        String followersCursor =
                service().connections("nasa", ConnectionType.FOLLOWERS, 50, null).nextCursor();

        assertThatThrownBy(() ->
                service().connections("nasa", ConnectionType.FOLLOWING, 50, followersCursor))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() ->
                service().connections("spacex", ConnectionType.FOLLOWERS, 50, followersCursor))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void refusesACursorThatIsNotOneOfOurs() {
        assertThatThrownBy(() ->
                service().connections("nasa", ConnectionType.FOLLOWERS, 50, "not-a-cursor"))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(apify.requests()).isEmpty();
    }

    // ---------------------------------------------------------------- caching

    @Test
    void servesARepeatedProfileLookupFromCacheWithoutPayingForARun() {
        apify.responds(200, "[{\"username\":\"nasa\",\"followersCount\":97000000}]");
        InstagramProfile first = service().profile("nasa");
        int callsAfterFirst = apify.requests().size();

        InstagramProfile second = service().profile("nasa");

        assertThat(apify.requests()).hasSize(callsAfterFirst);
        assertThat(second).isEqualTo(first);
    }

    @Test
    void servesARepeatedConnectionsPageFromCacheWithoutPayingForARun() {
        apify.responds(200, "[{\"username\":\"alice\"},{\"username\":\"bob\"}]");
        ConnectionsPage first = service().connections("nasa", ConnectionType.FOLLOWERS, 50, null);
        int callsAfterFirst = apify.requests().size();

        ConnectionsPage second = service().connections("nasa", ConnectionType.FOLLOWERS, 50, null);

        assertThat(apify.requests()).hasSize(callsAfterFirst);
        assertThat(second.accounts()).isEqualTo(first.accounts());
    }

    /**
     * Two page sizes are two different answers while a list is still being paged: each resumes
     * from somewhere else, so one cannot be served from the other's entry.
     */
    @Test
    void doesNotServeOneLimitFromAnotherPartialPagesCacheEntry() {
        apify.responds(200, "[{\"username\":\"alice\"},{\"username\":\"bob\"}]");
        apify.outputs("{\"continuations\":[{\"nextContinuationToken\":\"TOKEN-2\"}]}");
        service().connections("nasa", ConnectionType.FOLLOWERS, 50, null);
        int callsAfterFirst = apify.requests().size();

        service().connections("nasa", ConnectionType.FOLLOWERS, 200, null);

        assertThat(apify.requests().size()).isGreaterThan(callsAfterFirst);
        assertThat(cache.entries().keySet()).allSatisfy(key -> assertThat(key).doesNotContain(":all"));
    }

    /**
     * A page size is what we were willing to pay for in one run, not a property of the answer. Once
     * the actor says a list is finished, the whole list is the answer to any request big enough to
     * hold it — so it is stored once, under {@code all}, and a larger later request is free.
     */
    @Test
    void servesACompletedListToAnyLargerLimitWithoutPayingAgain() {
        apify.responds(200, "[{\"username\":\"alice\"},{\"username\":\"bob\"}]");
        service().connections("nasa", ConnectionType.FOLLOWERS, 50, null);
        int callsAfterFirst = apify.requests().size();

        ConnectionsPage bigger = service().connections("nasa", ConnectionType.FOLLOWERS, 500, null);

        assertThat(apify.requests()).hasSize(callsAfterFirst);
        assertThat(bigger.accounts()).extracting(InstagramAccount::username)
                .containsExactly("alice", "bob");
        assertThat(cache.entries()).hasSize(1);
        assertThat(cache.entries().keySet()).containsExactly("v3:followers:nasa:all");
    }

    /**
     * The one direction the {@code all} entry could lie in: a caller asking for fewer accounts than
     * the complete list holds asked for a page, and there is no cursor to hand it with a truncation.
     */
    @Test
    void doesNotServeACompletedListToASmallerLimitThanItHolds() {
        apify.responds(200, "[{\"username\":\"alice\"},{\"username\":\"bob\"}]");
        service().connections("nasa", ConnectionType.FOLLOWERS, 50, null);
        int callsAfterFirst = apify.requests().size();

        service().connections("nasa", ConnectionType.FOLLOWERS, 1, null);

        assertThat(apify.requests().size()).isGreaterThan(callsAfterFirst);
    }

    /**
     * An actor that cannot paginate never establishes the end of a list, so nothing it returns may
     * be stored as the whole one — a short page from it is the actor stopping, not the list ending.
     */
    @Test
    void neverStoresAnUnpaginatedActorsAnswerAsAWholeList() {
        apify.responds(200, "[{\"username\":\"alice\"}]");

        new InstagramService(
                new ApifyClient(apify.baseUrl(), "test-token", 120, NO_OP_LOGGER), cache,
                PROFILE_ACTOR, List.of(new DataDopingAdapter()), 100, 500, NO_OP_LOGGER)
                .connections("nasa", ConnectionType.FOLLOWERS, 50, null);

        assertThat(cache.entries().keySet()).allSatisfy(key -> assertThat(key).doesNotContain(":all"));
    }

    /** Followers and following are different lists; one must never answer for the other. */
    @Test
    void keepsFollowersAndFollowingInSeparateCacheEntries() {
        apify.responds(200, "[{\"username\":\"alice\"}]");
        service().connections("nasa", ConnectionType.FOLLOWERS, 50, null);
        int callsAfterFirst = apify.requests().size();

        service().connections("nasa", ConnectionType.FOLLOWING, 50, null);

        assertThat(apify.requests().size()).isGreaterThan(callsAfterFirst);
        assertThat(cache.entries().keySet())
                .anySatisfy(key -> assertThat(key).contains(":followers:"))
                .anySatisfy(key -> assertThat(key).contains(":following:"));
    }

    /** Each page of a walk is its own entry, so re-walking a list costs nothing. */
    @Test
    void cachesEachPageOfAWalkSeparately() {
        apify.responds(200, "[{\"username\":\"alice\"}]");
        apify.outputs("{\"continuations\":[{\"nextContinuationToken\":\"TOKEN-2\"}]}");
        String cursor = service().connections("nasa", ConnectionType.FOLLOWERS, 50, null).nextCursor();
        service().connections("nasa", ConnectionType.FOLLOWERS, 50, cursor);
        int callsAfterWalk = apify.requests().size();

        service().connections("nasa", ConnectionType.FOLLOWERS, 50, null);
        service().connections("nasa", ConnectionType.FOLLOWERS, 50, cursor);

        assertThat(apify.requests()).hasSize(callsAfterWalk);
        assertThat(cache.entries()).hasSize(2);
    }

    /** A cache outage should cost money, not availability. */
    @Test
    void keepsAnsweringWhenTheCacheIsUnreachable() {
        cache.breakIt();
        apify.responds(200, "[{\"username\":\"nasa\",\"followersCount\":5}]");

        assertThat(service().profile("nasa").followersCount()).isEqualTo(5L);
    }

    // ---------------------------------------------------------------- failures

    @Test
    void propagatesATimedOutRunAsSuch() {
        apify.responds(408, "{\"error\":{\"type\":\"request-timeout\"}}");

        assertThatThrownBy(() -> service().connections("nasa", ConnectionType.FOLLOWERS, 500, null))
                .isInstanceOfSatisfying(ApifyException.class, exception ->
                        assertThat(exception.kind()).isEqualTo(ApifyException.Kind.TIMED_OUT));
    }

    /**
     * An exhausted balance is not a broken actor. It keeps its own kind all the way out, which is
     * what earns it a distinct exit code — a script can alert on the bill rather than the actor.
     */
    @Test
    void keepsAnExhaustedApifyBalanceDistinctFromAFailedActor() {
        apify.responds(403, "{\"error\":{\"type\":\"monthly-usage-hard-limit-exceeded\"}}");

        assertThatThrownBy(() -> service().profile("nasa"))
                .isInstanceOfSatisfying(ApifyException.class, exception ->
                        assertThat(exception.kind()).isEqualTo(ApifyException.Kind.OUT_OF_CREDIT))
                .hasMessageContaining("usage credit");
    }

    @Test
    void keepsThrottlingDistinctToo() {
        apify.responds(429, "{\"error\":{\"type\":\"rate-limit-exceeded\"}}");

        assertThatThrownBy(() -> service().profile("nasa"))
                .isInstanceOfSatisfying(ApifyException.class, exception ->
                        assertThat(exception.kind()).isEqualTo(ApifyException.Kind.RATE_LIMITED));
    }

    @Test
    void reportsAnyOtherUpstreamFailureAsUnavailable() {
        apify.responds(500, "{\"error\":{\"type\":\"actor-failed\"}}");

        assertThatThrownBy(() -> service().profile("nasa"))
                .isInstanceOfSatisfying(ApifyException.class, exception ->
                        assertThat(exception.kind()).isEqualTo(ApifyException.Kind.UNAVAILABLE));
    }

    /**
     * The failure this was written for: on a free plan the connections actor hits a daily item
     * quota, exits 0, and returns an empty dataset — saying so only in OUTPUT. Believing that would
     * report "0 followers" for an account that has hundreds, and on a populated graph a complete
     * walk of zero followers retires every edge there is.
     */
    @Test
    void refusesAnEmptyRunTheActorItselfReportedAsFailed() {
        apify.responds(200, "[]");
        apify.outputs("""
                {"status":"FREE_API_DAILY_LIMIT_REACHED","success":false,
                 "message":"Extraction completed.","resultsDelivered":0,"continuations":[]}""");

        assertThatThrownBy(() -> service().connections("nasa", ConnectionType.FOLLOWERS, 500, null))
                .isInstanceOfSatisfying(ApifyException.class, exception ->
                        assertThat(exception.kind()).isEqualTo(ApifyException.Kind.RATE_LIMITED))
                .hasMessageContaining("FREE_API_DAILY_LIMIT_REACHED");
    }

    /** An OUTPUT that says the run went fine is not a failure, empty dataset or not. */
    @Test
    void acceptsAnEmptyRunTheActorReportedAsSuccessful() {
        apify.responds(200, "[]");
        apify.outputs("{\"success\":true,\"resultsDelivered\":0,\"continuations\":[]}");

        assertThat(service().connections("nasa", ConnectionType.FOLLOWERS, 50, null).accounts())
                .isEmpty();
    }

    @Test
    void reportsARunThatFailedAfterStarting() {
        apify.responds(200, "[]");
        apify.runSettlesAs("FAILED");

        assertThatThrownBy(() -> service().connections("nasa", ConnectionType.FOLLOWERS, 50, null))
                .isInstanceOfSatisfying(ApifyException.class, exception ->
                        assertThat(exception.kind()).isEqualTo(ApifyException.Kind.UNAVAILABLE));
    }

    /** Nothing should be remembered from a failed lookup. */
    @Test
    void doesNotCacheAFailedLookup() {
        apify.responds(500, "{\"error\":{\"type\":\"actor-failed\"}}");

        assertThatThrownBy(() -> service().connections("nasa", ConnectionType.FOLLOWERS, 50, null))
                .isInstanceOf(ApifyException.class);

        assertThat(cache.entries()).isEmpty();
    }

    private JsonNode actorInput() {
        try {
            return InstaJson.mapper().readTree(apify.runStart().body());
        } catch (Exception exception) {
            throw new AssertionError("The actor input was not JSON.", exception);
        }
    }

    private InstagramService service() {
        ApifyClient client = new ApifyClient(apify.baseUrl(), "test-token", 120, NO_OP_LOGGER);
        return new InstagramService(
                client, cache, PROFILE_ACTOR, List.of(new ScrapingSolutionsAdapter()),
                100, 500, NO_OP_LOGGER);
    }
}
