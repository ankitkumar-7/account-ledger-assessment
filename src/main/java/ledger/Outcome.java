package ledger;

/** What happened to one event, in a form the replay can print. */
public record Outcome(String eventId, boolean accepted, String summary) {

    static Outcome accepted(String eventId, String summary) {
        return new Outcome(eventId, true, summary);
    }

    static Outcome rejected(Rejection rejection) {
        return new Outcome(rejection.eventId(), false, "REJECTED: " + rejection.reason());
    }
}
