package com.paperwhale.domain.publisher;

/**
 * A label under which a publisher releases books, e.g. the lovingly curated
 * "Harpoon Classics" line of Pequod Press.
 */
public record Imprint(String label, Publisher publisher) {

    public String fullName() {
        return label + " (" + publisher.name() + ")";
    }
}
