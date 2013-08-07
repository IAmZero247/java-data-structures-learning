package com.github.davidmoten.structures.btree;

import static com.google.common.base.Optional.absent;
import static com.google.common.base.Optional.of;

import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.OutputStream;
import java.io.Serializable;
import java.util.Iterator;
import java.util.List;

import com.google.common.base.Optional;
import com.google.common.io.CountingInputStream;

public class NodeRef<T extends Serializable & Comparable<T>> {

	static final int CHILD_ABSENT = -1;

	private Optional<Position> position;

	private Optional<Node<T>> node = Optional.absent();
	private final NodeLoader<T> loader;

	private final int degree;

	private final boolean isRoot;

	public NodeRef(NodeLoader<T> nodeListener, Optional<Position> position,
			int degree, boolean isRoot) {
		this.loader = nodeListener;
		this.position = position;
		this.degree = degree;
		this.isRoot = isRoot;
	}

	synchronized Node<T> node() {
		if (!node.isPresent()) {
			if (position.isPresent()) {
				load();
			} else {
				node = of(new Node<T>(loader, this, isRoot));
			}
		}
		return node.get();
	}

	long load(InputStream is, Node<T> node) {
		try {
			CountingInputStream cis = new CountingInputStream(is);
			@SuppressWarnings("resource")
			ObjectInputStream ois = new ObjectInputStream(cis);
			node.setIsRoot(ois.readBoolean());
			// used for can delete for space recovery by LSS
			ois.readBoolean();
			int count = ois.readInt();
			Optional<Key<T>> previous = absent();
			Optional<Key<T>> first = absent();
			for (int i = 0; i < count; i++) {
				@SuppressWarnings("unchecked")
				T t = (T) ois.readObject();
				long leftFileNumber = ois.readLong();
				long left = ois.readLong();
				long rightFileNumber = ois.readLong();
				long right = ois.readLong();
				boolean deleted = ois.readBoolean();
				Key<T> key = new Key<T>(t);
				if (left != CHILD_ABSENT)
					key.setLeft(of(new NodeRef<T>(loader, of(new Position(
							leftFileNumber, left)), degree, false)));
				if (right != CHILD_ABSENT)
					key.setRight(of(new NodeRef<T>(loader, of(new Position(
							rightFileNumber, right)), degree, false)));
				key.setDeleted(deleted);
				key.setNext(Optional.<Key<T>> absent());
				if (!first.isPresent())
					first = of(key);
				if (previous.isPresent())
					previous.get().setNext(of(key));
				previous = of(key);
			}

			// don't close the input stream to avoid closing the underlying
			// stream
			node.setFirst(first);
			return cis.getCount();
		} catch (IOException e) {
			throw new RuntimeException(e);
		} catch (ClassNotFoundException e) {
			throw new RuntimeException(e);
		}
	}

	void load(InputStream is) {
		load(is, node.get());
	}

	private void load() {
		node = of(new Node<T>(loader, this, isRoot));
		loader.load(this);
	}

	public Optional<T> find(T t) {
		return node().find(t);
	}

	public long delete(T t) {
		return node().delete(t);
	}

	public List<? extends Key<T>> getKeys() {
		return node().getKeys();
	}

	public void setFirst(Optional<Key<T>> first) {
		node().setFirst(first);
	}

	public Optional<Key<T>> getFirst() {
		return node().getFirst();
	}

	public Iterator<T> iterator() {
		return node().iterator();
	}

	public String toString(String space) {
		return node().toString(space);
	}

	@Override
	public String toString() {
		if (node.isPresent()) {
			return node.toString();
		} else
			return asString();
	}

	public String asString() {
		StringBuilder builder = new StringBuilder();
		builder.append("NodeRef [position=");
		builder.append(position);
		builder.append("]");
		return builder.toString();
	}

	public Optional<Position> getPosition() {
		return position;
	}

	public void unload() {
		// System.out.println("unloaded " + position);
		node = absent();
	}

	public KeyNodes<T> add(KeyNodes<T> keyNodes) {
		return node().add(keyNodes);
	}

	void replaceKeySide(int keyIndex, Side side,
			NodeRef<T> lastNodeAddedToSaveQueue) {
		node().replaceKeySide(keyIndex, side, lastNodeAddedToSaveQueue);
	}

	KeyNodes<T> addToThisLevel(KeyNodes<T> keyNodes) {
		return node().addToThisLevel(keyNodes);
	}

	public Iterable<Key<T>> keys() {
		return node().keys();
	}

	public void save(OutputStream os) {
		node().save(os);
	}

	public void setPosition(Optional<Position> position) {
		this.position = position;
	}

	public int countKeys() {
		return node().countKeys();
	}

	public KeyNodes<T> split(KeyNodes<T> keyNodes) {
		return node().split(keyNodes);
	}

	KeyNodes<T> splitHere(KeyNodes<T> keyNodes) {
		return node().splitHere(keyNodes);
	}

	public void insertHere(Key<T> key) {
		node().insertHere(key);
	}

	public Key<T> key(int i) {
		return node().key(i);
	}

	public String abbr() {
		return node().abbr();
	}

	public boolean isRoot() {
		return node().isRoot();
	}

	public int getDegree() {
		return degree;
	}
}
