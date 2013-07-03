package com.github.davidmoten.structures.btree;

public class KeySide<T extends Comparable<T>> {
	private final Key<T> key;
	private final Side side;

	public KeySide(Key<T> key, Side side) {
		this.key = key;
		this.side = side;
	}

	public Key<T> getKey() {
		return key;
	}

	public Side getSide() {
		return side;
	}

	@Override
	public String toString() {
		return "KeyAndSide [key=" + key.value() + ", side=" + side + "]";
	}

}
