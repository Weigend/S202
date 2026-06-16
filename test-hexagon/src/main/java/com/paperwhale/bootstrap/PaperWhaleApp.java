package com.paperwhale.bootstrap;

import com.paperwhale.application.service.CatalogService;
import com.paperwhale.application.service.RestockService;
import com.paperwhale.application.service.SalesService;
import com.paperwhale.application.service.TrackingService;
import com.paperwhale.application.spi.BookRepository;
import com.paperwhale.application.spi.InventoryStore;
import com.paperwhale.application.spi.PublisherDirectory;
import com.paperwhale.domain.book.Book;
import com.paperwhale.domain.book.Genre;
import com.paperwhale.domain.book.Isbn;
import com.paperwhale.domain.inventory.ReorderPolicy;
import com.paperwhale.domain.inventory.StockItem;
import com.paperwhale.domain.publisher.Imprint;
import com.paperwhale.domain.publisher.Publisher;
import com.paperwhale.persistence.JdbcBookRepository;
import com.paperwhale.persistence.JdbcInventoryStore;
import com.paperwhale.persistence.JdbcPublisherDirectory;
import com.paperwhale.rest.CatalogResource;
import com.paperwhale.rest.TrackingResource;
import com.paperwhale.shipping.SeagullExpressCarrier;
import com.paperwhale.ui.ConsoleNotifier;
import com.paperwhale.ui.StoreFrontConsole;

/**
 * Composition root: the only place where adapters and the application core
 * meet. Wires the SPI implementations into the services, seeds the catalog,
 * and runs one busy afternoon at the Paper Whale.
 */
public final class PaperWhaleApp {

    public static void main(String[] args) {
        // --- driven adapters (SPI implementations) ---
        BookRepository books = new JdbcBookRepository();
        PublisherDirectory publishers = new JdbcPublisherDirectory();
        InventoryStore inventory = new JdbcInventoryStore();
        SeagullExpressCarrier carrier = new SeagullExpressCarrier();
        ConsoleNotifier notifier = new ConsoleNotifier();

        // --- application core ---
        ReorderPolicy policy = ReorderPolicy.standard();
        RestockService restock = new RestockService(books, inventory, publishers, carrier, policy);
        SalesService sales = new SalesService(books, inventory, notifier, restock, policy);
        CatalogService catalog = new CatalogService(books, inventory);
        TrackingService tracking = new TrackingService(carrier);

        // --- driving adapters ---
        StoreFrontConsole counter = new StoreFrontConsole(catalog, sales);
        CatalogResource catalogApi = new CatalogResource(catalog);
        TrackingResource trackingApi = new TrackingResource(tracking);

        seedCatalog(books, publishers, inventory);

        // --- one afternoon at the Paper Whale ---
        counter.showShelf(Genre.SEAFARING);

        counter.sellOverTheCounter("978-0-14-243724-7");
        counter.sellOverTheCounter("978-0-14-243724-7"); // drains stock -> triggers restock

        counter.showShelf(Genre.SEAFARING);

        System.out.println("REST clients see the same store:");
        System.out.println("GET /catalog?genre=mystery\n" + catalogApi.getCatalog("mystery"));
        System.out.println("GET /shipments/SGE-00042\n" + trackingApi.getShipment("SGE-00042"));
    }

    private static void seedCatalog(BookRepository books,
                                    PublisherDirectory publishers,
                                    InventoryStore inventory) {
        Publisher pequod = new Publisher("Pequod Press", "Nantucket", 1851);
        Publisher baskerville = new Publisher("Baskerville & Sons", "London", 1902);
        Publisher tintenfass = new Publisher("Tintenfass Verlag", "Heidelberg", 1979);

        Imprint harpoon = new Imprint("Harpoon Classics", pequod);
        Imprint hound = new Imprint("Hound Mysteries", baskerville);
        Imprint federkiel = new Imprint("Federkiel", tintenfass);
        publishers.register(harpoon);
        publishers.register(hound);
        publishers.register(federkiel);

        stock(books, inventory, new Book(new Isbn("978-0-14-243724-7"),
                "Moby-Dick", "Herman Melville", Genre.SEAFARING, harpoon, 1495), 2, 2);
        stock(books, inventory, new Book(new Isbn("978-0-19-953722-1"),
                "Twenty Thousand Leagues Under the Seas", "Jules Verne", Genre.SEAFARING, harpoon, 1250), 3, 2);
        stock(books, inventory, new Book(new Isbn("978-0-19-953696-5"),
                "The Hound of the Baskervilles", "Arthur Conan Doyle", Genre.MYSTERY, hound, 990), 4, 3);
        stock(books, inventory, new Book(new Isbn("978-3-12-345678-6"),
                "Das Fluestern der Tinte", "Greta Federkiel", Genre.FANTASY, federkiel, 1890), 2, 4);
    }

    private static void stock(BookRepository books,
                              InventoryStore inventory,
                              Book book,
                              int onShelf,
                              int inBackroom) {
        books.save(book);
        inventory.put(new StockItem(book.isbn(), onShelf, inBackroom));
    }
}
