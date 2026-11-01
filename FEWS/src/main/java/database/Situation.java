package database;

import javax.json.bind.annotation.JsonbPropertyOrder;

/**
 * One extracted fact grouped by a place and a time mentioned in its text.
 * An empty place or time means that side was not in the sentence.
 */
@JsonbPropertyOrder({"place", "time", "extract", "uri"})
public class Situation {
    private String place;
    private String time;
    private String extract;
    private String uri;

    public Situation() {}

    public Situation(String place, String time, String extract, String uri) {
        this.place = place;
        this.time = time;
        this.extract = extract;
        this.uri = uri;
    }

    public String getPlace() {
        return place;
    }

    public String getTime() {
        return time;
    }

    public String getExtract() {
        return extract;
    }

    public String getUri() {
        return uri;
    }
}
