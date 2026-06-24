package ru.arc.auction;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

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
