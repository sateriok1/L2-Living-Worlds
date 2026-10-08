# Testing

Pure checks, no server needed (Java 8+):

    javac -d out scripts/GrimoireIndex.java scripts/GrimoirePages.java tests/GrimoireTest.java
    java -cp out modules.geargrimoire.GrimoireTest data/gear_index.tsv

In game (`Enabled = True`, restart):

1. Alt+B shows a **Gear Grimoire** button. The front page has a search box and grade links.
2. Heavy, tank, A: Majestic Plate is on the list with its tier note; open it: four pieces, each with an Exchange line naming the sealed piece and Ancient Adena.
3. Open a piece: sources are listed; the drop-search link opens the stock drop board.
4. Type `.gg majestic`: a list of sets and pieces. `.gg tallum plate` goes straight to the set.
5. Weapons > A: strongest first, paging works, no page is cut off mid-row.
6. Tell me: do long exchange lines wrap OK, and is any page too tall for the board?
