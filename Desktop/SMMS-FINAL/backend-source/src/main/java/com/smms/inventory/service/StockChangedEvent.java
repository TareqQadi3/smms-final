package com.smms.inventory.service;

import java.util.Set;
import java.util.UUID;

/** Published inside the ledger transaction; the low-stock notifier reacts after commit. */
public record StockChangedEvent(Set<UUID> itemIds) {}
