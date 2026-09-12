package com.posassist;

import java.util.List;

/** Complete results or an exception. Implementations must never turn errors into empty stock. */
public interface InventorySource {
    List<Inventory.Store> stores() throws Exception;
    List<Inventory.Product> search(String text) throws Exception;
    List<Inventory.Product> products(List<String> ids) throws Exception;
    Inventory.Snapshot stock(List<Inventory.Line> lines) throws Exception;
    Inventory.Snapshot category(Inventory.Category category) throws Exception;
    void openTransfer() throws Exception;
    void openStoresum() throws Exception;
}
