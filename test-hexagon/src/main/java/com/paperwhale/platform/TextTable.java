package com.paperwhale.platform;

import com.paperwhale.application.api.ShelfEntry;

import java.util.List;

/**
 * Console formatting for the UI adapter and anyone else who likes boxes made
 * of dashes.
 */
public final class TextTable {

    public String shelf(List<ShelfEntry> entries) {
        StringBuilder out = new StringBuilder();
        out.append(String.format("%-42s %-22s %10s %8s%n", "TITLE", "AUTHOR", "PRICE", "ON SHELF"));
        out.append("-".repeat(86)).append(System.lineSeparator());
        for (ShelfEntry entry : entries) {
            out.append(String.format("%-42s %-22s %10s %8d%n",
                    entry.book().title(),
                    entry.book().author(),
                    entry.book().priceTag(),
                    entry.copiesOnShelf()));
        }
        return out.toString();
    }

    public String banner(String message) {
        String line = "─".repeat(message.length() + 4);
        return "┌" + line + "┐\n│  " + message + "  │\n└" + line + "┘";
    }
}
