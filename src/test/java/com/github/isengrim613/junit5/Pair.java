package com.github.isengrim613.junit5;

import java.util.Objects;

public class Pair<L, R> {
    private L left;
    private R right;

    public L getLeft() {
        return left;
    }

    public R getRight() {
        return right;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        Pair<?, ?> pair = (Pair<?, ?>) o;
        return Objects.equals(left, pair.left) &&
                Objects.equals(right, pair.right);
    }

    @Override
    public int hashCode() {

        return Objects.hash(left, right);
    }

    public static <L, R> Pair<L, R> of(L left, R right) {
        Pair<L, R> pair = new Pair<>();
        pair.left = left;
        pair.right = right;

        return pair;
    }
}