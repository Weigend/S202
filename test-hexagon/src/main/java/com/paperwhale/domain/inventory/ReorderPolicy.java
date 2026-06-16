package com.paperwhale.domain.inventory;

/**
 * When to reorder and how many copies to ask for. The Paper Whale reorders
 * early — nothing is sadder than an empty SEAFARING shelf.
 */
public record ReorderPolicy(int reorderBelow, int batchSize) {

    public static ReorderPolicy standard() {
        return new ReorderPolicy(3, 5);
    }

    public boolean needsRestock(StockItem item) {
        return item.totalCopies() < reorderBelow;
    }
}
