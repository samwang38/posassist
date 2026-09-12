package com.posassist;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/** Inventory domain. No Swing, EPB classes, persistence or network side effects. */
public final class Inventory {
    private Inventory() {}
    public static final int MAX_ITEMS = 30;
    public static final int MAX_QTY = 9999;
    public static final BigDecimal ZERO = BigDecimal.ZERO;

    public enum Category {
        MAC("Mac", "4001", "4002"), IPAD("iPad", "4005", "4006", "4041"),
        IPHONE("iPhone", "4004"), WATCH("Apple Watch", "4038");
        public final String label;
        public final List<String> codes;
        Category(String label, String... codes) {
            this.label = label; this.codes = Collections.unmodifiableList(Arrays.asList(codes));
        }
        public String toString() { return label; }
    }

    public static final class Product {
        public final String id, name, model, unit, cat3, cat4;
        public Product(String id, String name, String model, String unit, String cat3, String cat4) {
            this.id = required(id); this.name = required(name); this.model = clean(model);
            this.unit = required(unit); this.cat3 = clean(cat3); this.cat4 = clean(cat4);
        }
        public String toString() { return id + "　" + name; }
    }

    public static final class Store {
        public final String id, name, type;
        public Store(String id, String name, String type) {
            this.id = required(id); this.name = required(name); this.type = required(type);
            if (!id.matches("SA[0-9]{3}") || id.equals("SA999")
                    || !(type.equals("1_APR") || type.equals("2_AAR")))
                throw new IllegalArgumentException("非 SA 一般門市");
        }
        public String toString() { return name + " (" + id + ")"; }
    }

    public static final class Line {
        public final Product product;
        public final int quantity;
        public Line(Product product, int quantity) {
            if (product == null || quantity < 1 || quantity > MAX_QTY)
                throw new IllegalArgumentException("數量須為 1–" + MAX_QTY + " 的整數");
            this.product = product; this.quantity = quantity;
        }
    }

    /** EDT-owned draft. Changes invalidate any in-flight export via revision. */
    public static final class Draft {
        private final LinkedHashMap<String, Line> lines = new LinkedHashMap<String, Line>();
        private long revision;
        private String destination, source = "";
        public Draft(String destination) { this.destination = required(destination); }
        public List<Line> lines() { return new ArrayList<Line>(lines.values()); }
        public String destination() { return destination; }
        public String source() { return source; }
        public long revision() { return revision; }
        public void destination(String value) {
            if (!destination.equals(value)) { destination = required(value); source = ""; revision++; }
        }
        public void source(String value) {
            if (destination.equals(value)) throw new IllegalArgumentException("出入庫門市不可相同");
            source = clean(value); revision++;
        }
        public void add(Product product, int quantity) {
            Line old = lines.get(product.id);
            if (old == null && lines.size() >= MAX_ITEMS)
                throw new IllegalArgumentException("每份清單最多 " + MAX_ITEMS + " 項");
            long total = (old == null ? 0L : old.quantity) + quantity;
            if (quantity < 1 || total > MAX_QTY) throw new IllegalArgumentException("商品數量超出範圍");
            lines.put(product.id, new Line(product, (int) total)); revision++;
        }
        /** Synchronize membership immediately; quantities of still-selected codes are retained. */
        public void retainProducts(Collection<String> ids) {
            Iterator<String> it=lines.keySet().iterator();while(it.hasNext())if(!ids.contains(it.next()))it.remove();
            source="";revision++;
        }
        public void replaceProducts(List<Product> products) {
            LinkedHashMap<String,Line> replacement=new LinkedHashMap<String,Line>();
            for(Product p:products){Line old=lines.get(p.id);
                if(old!=null&&!old.product.unit.equals(p.unit))throw new IllegalArgumentException("商品 "+p.id+" 單位變更，請移除後重新選取");
                replacement.put(p.id,new Line(p,old==null?1:old.quantity));
            }
            if(replacement.size()>MAX_ITEMS)throw new IllegalArgumentException("每次比較最多 30 項");
            lines.clear();lines.putAll(replacement);source="";revision++;
        }
        public void quantity(String id, int quantity) {
            Line old = lines.get(id);
            if (old == null) throw new IllegalArgumentException("商品已移除");
            lines.put(id, new Line(old.product, quantity)); revision++;
        }
        /** Atomic union for native selections: existing quantities never increment. */
        public void mergeProducts(List<Product> products) {
            LinkedHashMap<String,Line> merged=new LinkedHashMap<String,Line>(lines);
            for(Product p:products){
                Line old=merged.get(p.id);
                if(old!=null&&!old.product.unit.equals(p.unit))throw new IllegalArgumentException("商品 "+p.id+" 單位變更，請移除後重新選取");
                merged.put(p.id,new Line(p,old==null?1:old.quantity));
            }
            if(merged.size()>MAX_ITEMS)throw new IllegalArgumentException("累積比較最多 30 項，請先移除商品或清空重新比較");
            lines.clear();lines.putAll(merged);revision++;
        }
        public void remove(String id) { lines.remove(id); revision++; }
        public void clear() { lines.clear(); source = ""; revision++; }
    }

    /** Construct ONLY after a complete successful response; missing rows then mean zero. */
    public static final class Snapshot {
        public final List<Store> stores;
        public final Map<String, Product> products;
        private final Map<String, BigDecimal> quantities;
        public final long checkedAt;
        public Snapshot(List<Store> stores, Collection<Product> products,
                        Map<String, BigDecimal> quantities, long checkedAt) {
            Set<String> ids = new HashSet<String>();
            for (Store s : stores) if (!ids.add(s.id)) throw new IllegalArgumentException("門市對照重複");
            LinkedHashMap<String, Product> ps = new LinkedHashMap<String, Product>();
            for (Product p : products) {
                if (ps.put(p.id, p) != null) throw new IllegalArgumentException("商品資料重複");
            }
            for (Map.Entry<String, BigDecimal> e : quantities.entrySet()) {
                String[] parts = e.getKey().split("\\|", -1);
                if (parts.length != 2 || !ids.contains(parts[0]) || !ps.containsKey(parts[1]) || e.getValue() == null)
                    throw new IllegalArgumentException("庫存資料不完整");
            }
            this.stores = Collections.unmodifiableList(new ArrayList<Store>(stores));
            this.products = Collections.unmodifiableMap(ps);
            this.quantities = Collections.unmodifiableMap(new HashMap<String, BigDecimal>(quantities));
            this.checkedAt = checkedAt;
        }
        public Store store(String id) {
            for (Store s : stores) if (s.id.equals(id)) return s;
            throw new IllegalArgumentException("門市不在本次可查詢範圍");
        }
        public BigDecimal qty(String store, String product) {
            store(store);
            if (!products.containsKey(product)) throw new IllegalArgumentException("商品不在本次完整查詢內");
            BigDecimal q = quantities.get(key(store, product));
            return q == null ? ZERO : q;
        }
    }

    public static final class Candidate {
        public final Store store;
        public final int fulfilled, exhausted, total;
        public final BigDecimal remaining;
        Candidate(Store store, List<Line> lines, Snapshot snapshot) {
            this.store = store; total = lines.size();
            int f = 0, e = 0; BigDecimal left = ZERO;
            for (Line line : lines) {
                BigDecimal after = snapshot.qty(store.id, line.product.id).subtract(BigDecimal.valueOf(line.quantity));
                if (after.signum() >= 0) f++;
                if (after.signum() == 0) e++;
                left = left.add(after);
            }
            fulfilled = f; exhausted = e; remaining = left;
        }
        public boolean sufficient() { return total > 0 && fulfilled == total; }
    }

    public static List<Candidate> candidates(Snapshot snapshot, List<Line> lines, String destination, boolean all) {
        snapshot.store(destination);
        List<Candidate> result = new ArrayList<Candidate>();
        if (lines.isEmpty()) return result;
        for (Store store : snapshot.stores) {
            if (store.id.equals(destination)) continue;
            Candidate c = new Candidate(store, lines, snapshot);
            if (!all || c.sufficient()) result.add(c);
        }
        Collections.sort(result, new Comparator<Candidate>() {
            public int compare(Candidate a, Candidate b) {
                int c = Integer.compare(b.fulfilled, a.fulfilled);
                if (c == 0) c = Integer.compare(a.exhausted, b.exhausted);
                if (c == 0) c = b.remaining.compareTo(a.remaining);
                return c == 0 ? a.store.id.compareTo(b.store.id) : c;
            }
        });
        return result;
    }

    public static final class Shortage {
        public final Product product;
        public final BigDecimal local, median, difference;
        public final int stocked, peers;
        public final boolean outOfStock;
        Shortage(Product p, BigDecimal local, BigDecimal median, int stocked, int peers) {
            product = p; this.local = local; this.median = median; this.stocked = stocked; this.peers = peers;
            difference = median.setScale(0, RoundingMode.CEILING).subtract(local).max(ZERO);
            outOfStock = local.signum() <= 0 && stocked > 0;
        }
    }
    public static List<Shortage> shortages(Snapshot snapshot, String destination) {
        Store home = snapshot.store(destination);
        List<Store> peers = new ArrayList<Store>();
        for (Store s : snapshot.stores) if (!s.id.equals(home.id) && s.type.equals(home.type)) peers.add(s);
        List<Shortage> result = new ArrayList<Shortage>();
        if (peers.isEmpty()) return result;
        for (Product p : snapshot.products.values()) {
            List<BigDecimal> qs = new ArrayList<BigDecimal>(); int stocked = 0;
            for (Store s : peers) { BigDecimal q = snapshot.qty(s.id, p.id); qs.add(q); if (q.signum() > 0) stocked++; }
            Collections.sort(qs); int n = qs.size();
            BigDecimal median = n % 2 == 1 ? qs.get(n / 2)
                : qs.get(n / 2 - 1).add(qs.get(n / 2)).divide(BigDecimal.valueOf(2));
            Shortage row = new Shortage(p, snapshot.qty(home.id, p.id), median, stocked, peers.size());
            if (row.outOfStock || row.local.compareTo(row.median) < 0) result.add(row);
        }
        Collections.sort(result, new Comparator<Shortage>() {
            public int compare(Shortage a, Shortage b) {
                int c = Boolean.compare(b.outOfStock, a.outOfStock);
                if (c == 0) c = b.difference.compareTo(a.difference);
                return c == 0 ? a.product.id.compareTo(b.product.id) : c;
            }
        });
        return result;
    }

    public static String export(Snapshot current, List<Line> lines, String source, String destination,
                                String purpose, String note, boolean full) {
        if (source.equals(destination) || lines.isEmpty()) throw new IllegalArgumentException("請先選擇來源店與商品");
        Store from = current.store(source), to = current.store(destination);
        Candidate check = new Candidate(from, lines, current);
        if (!check.sufficient()) throw new IllegalArgumentException("來源店庫存已不足，請調整清單");
        StringBuilder s = new StringBuilder();
        if (full) {
            s.append("調撥草稿（尚未建立 EPB 單據）\n出庫：").append(from).append("\n入庫：").append(to)
             .append("\n用途：").append(purpose).append("\n備註：").append(clean(note))
             .append("\n帳面庫存核對：").append(stamp(current.checkedAt)).append("\n商品代碼\t品名\t數量\n");
        }
        for (Line line : lines) {
            s.append(line.product.id).append('\t');
            if (full) s.append(line.product.name.replace('\t', ' ').replace('\n', ' ')).append('\t');
            s.append(line.quantity).append('\n');
        }
        return s.toString();
    }
    public static String key(String store, String product) { return store + "|" + product; }
    public static String number(BigDecimal value) { return value.stripTrailingZeros().toPlainString(); }
    public static String stamp(long ms) {
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("MM/dd HH:mm:ss");
        f.setTimeZone(TimeZone.getTimeZone("Asia/Taipei")); return f.format(new Date(ms));
    }
    static String clean(String value) { return value == null ? "" : value.trim(); }
    static String required(String value) {
        String text = clean(value);
        if (text.isEmpty()) throw new IllegalArgumentException("必要欄位缺失");
        return text;
    }
}
