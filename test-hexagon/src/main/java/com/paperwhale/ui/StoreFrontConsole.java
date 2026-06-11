package com.paperwhale.ui;

import com.paperwhale.application.api.BrowseCatalogUseCase;
import com.paperwhale.application.api.SaleReceipt;
import com.paperwhale.application.api.SellBookUseCase;
import com.paperwhale.domain.book.Genre;
import com.paperwhale.domain.book.Isbn;
import com.paperwhale.platform.TextTable;

/**
 * Driving adapter: the shop counter. Talks to the application core through
 * the inbound ports only — it has no idea where books are stored or how
 * seagulls are dispatched.
 */
public final class StoreFrontConsole {

    private final BrowseCatalogUseCase browseCatalog;
    private final SellBookUseCase sellBook;
    private final TextTable textTable = new TextTable();

    public StoreFrontConsole(BrowseCatalogUseCase browseCatalog, SellBookUseCase sellBook) {
        this.browseCatalog = browseCatalog;
        this.sellBook = sellBook;
    }

    public void showShelf(Genre genre) {
        System.out.println(textTable.banner("THE PAPER WHALE — " + genre + " shelf"));
        System.out.println(textTable.shelf(browseCatalog.browseShelf(genre)));
    }

    public void sellOverTheCounter(String isbn) {
        SaleReceipt receipt = sellBook.sellOne(new Isbn(isbn));
        System.out.printf("Sold \"%s\" for %s — %d copies left.%n",
                receipt.book().title(), receipt.book().priceTag(), receipt.copiesLeft());
        receipt.restockTriggered().ifPresent(info ->
                System.out.println("  ↳ restock triggered: " + info.summary()
                        + " [" + info.trackingCode().value() + "]"));
    }
}
