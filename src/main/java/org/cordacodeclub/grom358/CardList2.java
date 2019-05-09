/*
 * Copyright 2012 Cameron Zemek <grom358@gmail.com>.
 */
package org.cordacodeclub.grom358;

import org.cordacodeclub.grom356.Card;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A list of poker cards
 *
 * @author Cameron Zemek <grom358@gmail.com>
 */
public class CardList2 extends ArrayList<Card> {
    public CardList2() {
        super();
    }

    public CardList2(int initialCapacity) {
        super(initialCapacity);
    }

    public CardList2(Collection<Card> cards) {
        super(cards);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append('[');
        Iterator it = this.iterator();
        if (it.hasNext()) {
            sb.append(it.next());
        }
        while (it.hasNext()) {
            sb.append(',');
            sb.append(it.next());
        }
        sb.append(']');
        return sb.toString();
    }

    static final private Pattern listPattern = Pattern.compile("[0-9TJQKA][cdhs]");

    static public CardList2 valueOf(String str) {
        Matcher matcher = listPattern.matcher(str);
        CardList2 cardList = new CardList2();
        while (matcher.find()) {
            Card card = Card.valueOf(matcher.group());
            cardList.add(card);
        }
        return cardList;
    }
}
