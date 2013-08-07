package com.github.davidmoten.structures.btree;

import static com.google.common.base.Optional.absent;
import static com.google.common.base.Optional.of;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;

/**
 * A standard BTree implementation as per wikipedia entry with some tweaks to
 * add in concurrency. In particular, iteration through the btree can be done
 * concurrently with addition/deletion with the side-effect that the iteration
 * might include changes to the tree since the iterator was created.
 * 
 * @author dxm
 * 
 * @param <T>
 */
public class BTree<T extends Serializable & Comparable<T>> implements
		Iterable<T> {

	/**
	 * The root node. Mutable!
	 */
	private NodeRef<T> root;

	/**
	 * The maximum number of keys in a node plus one.
	 */
	private final int degree;

	/**
	 * The file the btree is persisted to.
	 */
	private final Optional<File> metadataFile;

	/**
	 * Manages allocation of file positions for nodes.
	 */
	private final Optional<Storage> storage;

	/**
	 * This object is synchronized on to ensure that adds and deletes happen one
	 * at a time (synchronously).
	 */
	private final Object writeMonitor = new Object();

	/**
	 * This object is synchronized on to ensure that metadata writes happen one
	 * at a time (synchronously).
	 */
	private final Object metadataMonitor = new Object();

	/**
	 * Allows reduction in memory usage for large btrees.
	 */
	private final Optional<NodeCache<T>> nodeCache;

	/**
	 * Queues nodes for saving.
	 */
	private final LinkedList<NodeRef<T>> saveQueue = new LinkedList<NodeRef<T>>();

	/**
	 * Loads the node pointed to by the NodeRef from persistent storage.
	 */
	private final NodeLoader<T> loader = new NodeLoader<T>() {

		@Override
		public void load(NodeRef<T> node) {
			BTree.this.load(node);
		}

	};

	/**
	 * @param cls
	 * @param degree
	 *            will be overriden by value in metadata file if exists
	 * @param file
	 *            is used as base file name
	 * @param keySizeBytes
	 *            will be overriden by value in metadata file if exists
	 * @param cacheSize
	 *            - if absent not cache used
	 */
	private BTree(Builder<T> builder) {
		Preconditions.checkNotNull(builder.metadataFile, "file cannot be null");
		Preconditions.checkNotNull(builder.degree, "degree cannot be null");
		Preconditions.checkNotNull(builder.cacheSize,
				"cacheSize cannot be null");
		Preconditions.checkArgument(builder.degree.isPresent()
				|| builder.metadataFile.isPresent()
				&& builder.metadataFile.get().exists(),
				"must specify degree or use an existing file");
		Preconditions.checkArgument(!builder.degree.isPresent()
				|| builder.degree.get() >= 2, "degree must be >=2");

		if (builder.cacheSize.isPresent())
			nodeCache = of(new NodeCache<T>(builder.cacheSize.get()));
		else
			nodeCache = absent();

		this.metadataFile = builder.metadataFile;

		if (metadataFile.isPresent()) {
			if (metadataFile.get().exists()) {

				Metadata metadata = readMetadata(builder.degree);
				degree = metadata.degree;
				root = new NodeRef<T>(loader, of(metadata.rootPosition), degree);

				if (!builder.storage.isPresent())
					this.storage = of(new Storage(metadataFile.get()
							.getParentFile(), metadataFile.get().getName()
							+ ".storage", metadata.fileNumber));
				else {
					this.storage = builder.storage;
				}
			} else {
				degree = builder.degree.get();
				root = new NodeRef<T>(loader, Optional.<Position> absent(),
						degree);
				if (!builder.storage.isPresent())
					this.storage = of(new Storage(metadataFile.get()
							.getParentFile(), metadataFile.get().getName()
							+ ".storage", 0));
				else {
					this.storage = builder.storage;
				}
			}
		} else {
			this.storage = absent();
			this.degree = builder.degree.get();
			root = new NodeRef<T>(loader, Optional.<Position> absent(), degree);
			addToSaveQueue(root);
			flushSaves(saveQueue);
			if (metadataFile.isPresent())
				writeMetadata();
		}
	}

	/**
	 * Builder for a {@link BTree}.
	 * 
	 * @author dxm
	 * 
	 * @param <R>
	 */
	public static class Builder<R extends Serializable & Comparable<R>> {
		private Optional<Integer> degree = of(100);
		private Optional<File> metadataFile = absent();
		private Optional<Long> cacheSize = absent();
		private Optional<Storage> storage = absent();

		/**
		 * Constructor.
		 * 
		 */
		public Builder() {
		}

		/**
		 * Sets the degree.
		 * 
		 * @param degree
		 * @return
		 */
		public Builder<R> degree(int degree) {
			this.degree = of(degree);
			return this;
		}

		/**
		 * Sets the file.
		 * 
		 * @param file
		 * @return
		 */
		public Builder<R> metadata(File file) {
			this.metadataFile = of(file);
			return this;
		}

		/**
		 * Sets the size of the node cache being the number of nodes that are
		 * kept loaded in memory.
		 * 
		 * @param cacheSize
		 * @return
		 */
		public Builder<R> cacheSize(long cacheSize) {
			this.cacheSize = of(cacheSize);
			return this;
		}

		public Builder<R> storage(Storage storage) {
			this.storage = of(storage);
			return this;
		}

		/**
		 * Returns a new {@link BTree}.
		 * 
		 * @return
		 */
		public BTree<R> build() {
			return new BTree<R>(this);
		}
	}

	/**
	 * Notifies the nodeCache that a node has been loaded (or saved for the
	 * first time).
	 * 
	 * @param position
	 * @param node
	 */
	void loaded(long position, NodeRef<T> node) {
		if (nodeCache.isPresent())
			nodeCache.get().put(position, node);
	}

	/**
	 * Metdata for the btree.
	 * 
	 */
	private static class Metadata {
		final Position rootPosition;
		final int degree;
		final long fileNumber;

		/**
		 * Constructor.
		 * 
		 * @param rootPosition
		 * @param degree
		 */
		public Metadata(Position rootPosition, int degree, long fileNumber) {
			super();
			this.rootPosition = rootPosition;
			this.degree = degree;
			this.fileNumber = fileNumber;
		}
	}

	/**
	 * Reads the metadata information from the file including the position of
	 * the root node.
	 */
	private Metadata readMetadata(Optional<Integer> defaultDegree) {
		try {
			ObjectInputStream ois = new ObjectInputStream(new FileInputStream(
					metadataFile.get()));
			long fileNumber = ois.readLong();
			long rootFileNumber = ois.readLong();
			long rootPosition = ois.readLong();
			int degree = ois.readInt();
			ois.close();
			return new Metadata(new Position(rootFileNumber, rootPosition),
					degree, fileNumber);
		} catch (FileNotFoundException e) {
			throw new RuntimeException(e);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	/**
	 * Writes the metadata information to the file including the position of the
	 * root node.
	 */
	private void writeMetadata() {
		synchronized (metadataMonitor) {
			try {
				if (!metadataFile.get().exists())
					metadataFile.get().createNewFile();
				FileOutputStream fos = new FileOutputStream(metadataFile.get());
				byte[] bytes = composeMetadata();
				fos.write(bytes);
				fos.close();
			} catch (FileNotFoundException e) {
				throw new RuntimeException(e);
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		}
	}

	/**
	 * Returns the bytes containing the metadata information for this BTree.
	 * 
	 * @return
	 * @throws IOException
	 */
	private byte[] composeMetadata() throws IOException {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		ObjectOutputStream oos = new ObjectOutputStream(bytes);
		oos.writeLong(storage.get().getFileNumber());
		oos.writeLong(root.getPosition().get().getFileNumber());
		oos.writeLong(root.getPosition().get().getPosition());
		oos.writeInt(degree);
		oos.close();
		return bytes.toByteArray();
	}

	/**
	 * Creates a {@link Builder}.
	 * 
	 * @param cls
	 *            - used for type inference only.
	 * @return
	 */
	public static <R extends Comparable<R> & Serializable> Builder<R> builder(
			Class<R> cls) {
		return new Builder<R>();
	}

	/**
	 * Adds one or more elements to the b-tree. May replace root.
	 * 
	 * @param t
	 */
	public BTree<T> add(T... values) {
		for (T t : values) {
			addOne(t);
		}
		return this;
	}

	/**
	 * Adds a value to the root node and replaces the root node and updates
	 * metadata file if present.
	 * 
	 * @param t
	 */
	private void addOne(T t) {
		KeyNodes<T> keyNodes = root.add(KeyNodes.create(new Key<T>(t)));
		for (NodeRef<T> node : keyNodes.getSaveQueue()) {
			saveQueue.add(node);
		}
		NodeRef<T> node;
		if (keyNodes.getKey().isPresent()) {
			node = new NodeRef<T>(loader, Optional.<Position> absent(), degree);
			node.setFirst(of(keyNodes.getKey().get()));
			saveQueue.add(node);
		} else
			node = keyNodes.getSaveQueue().getLast();

		flushSaves(saveQueue);
		root = node;
		if (metadataFile.isPresent())
			writeMetadata();
	}

	/**
	 * Flushes queued saves to disk if storage present.
	 * 
	 * @param saveQueue2
	 */
	private void flushSaves(LinkedList<NodeRef<T>> saveQueue) {
		if (storage.isPresent()) {
			storage.get().save(saveQueue);
			for (NodeRef<T> node : saveQueue)
				loaded(node.getPosition().get().getPosition(), node);
		}
		saveQueue.clear();
	}

	/**
	 * Returns the first T found that equals t from this b-tree.
	 * 
	 * @param t
	 * @return
	 */
	public Optional<T> find(T t) {
		return root.find(t);
	}

	/**
	 * Returns the result of a range query.
	 * 
	 * @param t1
	 * @param t2
	 * @param op1
	 * @param op2
	 * @return
	 */
	public Iterable<Optional<T>> find(T t1, T t2, ComparisonOperator op1,
			ComparisonOperator op2) {
		return null;
	}

	/**
	 * Deletes (or marks as deleted) those keys in the BTree that match one of
	 * the keys in the parameter.
	 * 
	 * @param keys
	 * @return
	 */
	public long delete(T... keys) {
		long count = 0;
		for (T key : keys)
			count += deleteOne(key);
		return count;
	}

	/**
	 * Deletes (or marks as deleted) all keys in the BTree that equal
	 * <code>key</code>.
	 * 
	 * @param key
	 * @return
	 */
	private long deleteOne(T key) {
		synchronized (writeMonitor) {
			return root.delete(key);
		}
	}

	/**
	 * Returns the keys as a {@link List}.
	 * 
	 * @return
	 */
	@VisibleForTesting
	List<? extends Key<T>> getKeys() {
		return root.getKeys();
	}

	@Override
	public Iterator<T> iterator() {
		return root.iterator();
	}

	/**
	 * Adds a node to the save queue.
	 * 
	 * @param node
	 */
	private void addToSaveQueue(NodeRef<T> node) {
		saveQueue.push(node);
	}

	/**
	 * Loads a node from disk.
	 * 
	 * @param node
	 */
	private void load(NodeRef<T> node) {
		if (storage.isPresent()) {
			storage.get().load(node);
			loaded(node.getPosition().get().getPosition(), node);
		}
	}

	/**
	 * Writes information about the current file to stdout.
	 */
	public void displayFile() {
		try {
			System.out.println("------------ File contents ----------------");
			System.out.println(storage.get().getFile());
			long length = storage.get().getFile().length();
			System.out.println("length=" + length);
			FileInputStream fis = new FileInputStream(storage.get().getFile());
			int pos = 0;
			while (pos < length) {
				NodeRef<T> ref = new NodeRef<T>(loader,
						Optional.<Position> absent(), degree);
				Node<T> node = new Node<T>(loader, ref, degree);
				long size = ref.load(fis, node);
				displayNode(pos, node);
				pos += size;
				System.out.println("pos=" + pos);
			}
			System.out.println("------------");
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	/**
	 * Writes information about the given node to stdout.
	 * 
	 * @param pos
	 * @param node
	 */
	private void displayNode(long pos, Node<T> node) {
		System.out.println("node position=" + pos);
		for (Key<T> key : node.keys()) {
			String left;
			if (key.getLeft().isPresent())
				left = key.getLeft().get().getPosition().get() + "";
			else
				left = "";
			String right;
			if (key.getRight().isPresent())
				right = key.getRight().get().getPosition().get() + "";
			else
				right = "";
			System.out.println("    key " + key.value() + " L=" + left + " R="
					+ right);
		}
	}

	@Override
	public String toString() {
		StringBuilder builder = new StringBuilder();
		builder.append("BTree [root=");
		builder.append(root);
		builder.append("]");
		return builder.toString();
	}

	/**
	 * Returns an abbreviated string representation of the btree.
	 * 
	 * @return
	 */
	public String abbr() {
		return root.abbr();
	}

}
