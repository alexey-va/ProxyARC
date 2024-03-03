package ru.arc;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AuctionItemDto {
    String display;
    String seller;
    String price;
    long expire;
    String category;
    int amount;
    int priority;
    String uuid;
    boolean exist;
    List<String> lore = new ArrayList<>();
}
