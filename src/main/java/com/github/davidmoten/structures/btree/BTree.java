package com.github.davidmoten.structures.btree;

import static com.google.common.base.Optional.absent;
import static com.google.common.base.Optional.of;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.RandomAccessFile;
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
	 * The root node.
	 */
	private NodeRef<T> root;

	/**
	 * The maximum number of keys in a node plus one.
	 */
	private int degree;

	/**
	 * The file the btree is persisted to.
	 */
	private final Optional<File> file;

	/**
	 * The maximum number of bytes required to serialize T.
	 */
	private int keySizeBytes;

	/**
	 * Where node storage starts in the file.
	 */
	private final static long METADATA_LENGTH = 1000;

	/**
	 * The current position in the file of the root node.
	 */
	private Optional<Long> rootPosition = of(0L);
	/**
	 * Manages allocation of file positions for nodes.
	 */
	private final PositionManager positionManager;

	/**
	 * This object is synchronized on to ensure that adds and deletes happen one
	 * at a time (synchronously).
	 */
	private final Object writeMonitor = new Object();

	private final Optional<NodeCache<T>> nodeCache;

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
	private BTree(Class<T> cls, Optional<Integer> degree, Optional<File> file,
			Optional<Integer> keySizeBytes, Optional<Long> cacheSize) {
		Preconditions.checkNotNull(file, "file cannot be null");
		Preconditions.checkNotNull(degree, "degree cannot be null");
		Preconditions.checkNotNull(keySizeBytes, "keySize cannot be null");
		Preconditions.checkNotNull(cacheSize, "cacheSize cannot be null");
		Preconditions.checkArgument(degree.isPresent() || file.isPresent()
				&& file.get().exists(),
				"must specify degree or use an existing file");
		Preconditions.checkArgument(
				keySizeBytes.isPresent() || file.isPresent()
						&& file.get().exists(),
				"must specify keySize or use an existing file");
		Preconditions.checkArgument(!degree.isPresent() || degree.get() >= 2,
				"degree must be >=2");
		Preconditions.checkArgument(
				!keySizeBytes.isPresent() || keySizeBytes.get() > 0,
				"keySize must be >0");
		if (degree.isPresent())
			this.degree = degree.get();
		if (keySizeBytes.isPresent())
			this.keySizeBytes = keySizeBytes.get();
		this.file = file;
		this.positionManager = new PositionManager(file);
		if (cacheSize.isPresent())
			nodeCache = of(new NodeCache<T>(cacheSize.get()));
		else
			nodeCache = absent();
		if (file.isPresent() && file.get().exists()) {
			readHeader();
			root = new NodeRef<T>(this, rootPosition);
		} else {
			if (file.isPresent())
				writeHeader();
			root = new NodeRef<T>(this, Optional.<Long> absent());
		}
	}

	void loaded(long position, NodeRef<T> node) {
		if (nodeCache.isPresent())
			nodeCache.get().put(position, node);
	}

	private Optional<File> getHeaderFile() {
		if (file.isPresent())
			return of(new File(file.get().getParentFile(), file.get().getName()
					+ ".metadata"));
		else
			return absent();
	}

	/**
	 * Reads the header information from the file including the position of the
	 * root node.
	 */
	private void readHeader() {
		try {
			RandomAccessFile f = new RandomAccessFile(getHeaderFile().get(),
					"r");
			byte[] header = new byte[(int) METADATA_LENGTH];
			f.seek(0);
			f.read(header);
			f.close();
			ObjectInputStream ois = new ObjectInputStream(
					new ByteArrayInputStream(header));
			rootPosition = Optional.of(ois.readLong());
			degree = ois.readInt();
			keySizeBytes = ois.readInt();
			ois.close();
		} catch (FileNotFoundException e) {
			throw new RuntimeException(e);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	/**
	 * Writes the header information to the file including the position of the
	 * root node.
	 */
	private synchronized void writeHeader() {
		try {
			if (!file.get().exists())
				file.get().createNewFile();
			RandomAccessFile f = new RandomAccessFile(getHeaderFile().get(),
					"rw");
			ByteArrayOutputStream header = new ByteArrayOutputStream(
					(int) METADATA_LENGTH);
			ObjectOutputStream oos = new ObjectOutputStream(header);
			oos.writeLong(rootPosition.get());
			oos.writeInt(degree);
			oos.writeInt(keySizeBytes);
			oos.close();

			f.seek(0);
			f.write(header.toByteArray());
			if (header.size() < METADATA_LENGTH) {
				byte[] more = new byte[(int) METADATA_LENGTH - header.size()];
				f.write(more);
			}
			f.close();
		} catch (FileNotFoundException e) {
			throw new RuntimeException(e);
		} catch (IOException e) {
			throw new RuntimeException(e);
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
		private Optional<File> file = absent();
		private Optional<Integer> keySizeBytes = of(100);
		private final Class<R> cls;
		private Optional<Long> cacheSize = of(100L);

		/**
		 * Constructor.
		 * 
		 * @param cls
		 */
		public Builder(Class<R> cls) {
			this.cls = cls;
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
		public Builder<R> file(File file) {
			this.file = of(file);
			return this;
		}

		/**
		 * Sets the keySize.
		 * 
		 * @param keySizeBytes
		 * @return
		 */
		public Builder<R> keySizeBytes(int keySizeBytes) {
			this.keySizeBytes = of(keySizeBytes);
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

		/**
		 * Returns a new {@link BTree}.
		 * 
		 * @return
		 */
		public BTree<R> build() {
			return new BTree<R>(cls, degree, file, keySizeBytes, cacheSize);
		}
	}

	/**
	 * Creates a {@link Builder}.
	 * 
	 * @param cls
	 * @return
	 */
	public static <R extends Comparable<R> & Serializable> Builder<R> builder(
			Class<R> cls) {
		return new Builder<R>(cls);
	}

	/**
	 * Returns the degree (the max number of keys in a node plus one).
	 * 
	 * @return
	 */
	public int getDegree() {
		return degree;
	}

	/**
	 * Adds one or more elements to the b-tree. May replace root.
	 * 
	 * @param t
	 */
	public BTree<T> add(T... values) {
		for (T t : values)
			addOne(t);
		return this;
	}

	private void addOne(T t) {
		synchronized (writeMonitor) {
			AddResult<T> result = root.add(t);
			final NodeRef<T> node;
			if (result.getSplitKey().isPresent()) {
				node = new NodeRef<T>(this, Optional.<Long> absent());
				node.setFirst(result.getSplitKey());
				addToSaveQueue(node);
			} else {
				// note that new node has already been saved so don't need to
				// call save here
				node = result.getNode().get();
			}
			flushSaves();
			root = node;
			rootPosition = root.getPosition();
			if (file.isPresent())
				writeHeader();
		}
	}

	private void flushSaves() {
		if (getFile().isPresent()) {
			ByteArrayOutputStream allBytes = new ByteArrayOutputStream();
			long startPos = positionManager.nextPosition();
			long pos = startPos;
			while (!saveQueue.isEmpty()) {
				NodeRef<T> node = saveQueue.removeLast();

				ByteArrayOutputStream bytes = new ByteArrayOutputStream();
				node.save(bytes);
				node.setPosition(Optional.of(pos));

				try {
					allBytes.write(bytes.toByteArray());
					int remainingBytes = nodeLengthBytes() - bytes.size();
					if (remainingBytes < 0)
						throw new RuntimeException(
								"max node length not big enough for its keys");
					else if (remainingBytes > 0)
						// write blank bytes
						allBytes.write(new byte[remainingBytes]);
				} catch (IOException e) {
					throw new RuntimeException(e);
				}

				pos += nodeLengthBytes();

				loaded(node.getPosition().get(), node);
			}
			saveToFile(allBytes, startPos);
		}
	}

	private void saveToFile(ByteArrayOutputStream allBytes, long startPos) {
		try {
			RandomAccessFile f = new RandomAccessFile(getFile().get(), "rw");
			f.seek(startPos);
			writeBytes(f, allBytes);
			f.close();

		} catch (FileNotFoundException e) {
			throw new RuntimeException(e);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
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
	 * Returns the file the btree is being persisted to. Returns
	 * Optional.absent() if none defined.
	 * 
	 * @return
	 */
	public Optional<File> getFile() {
		return file;
	}

	/**
	 * Returns the key size in bytes.
	 * 
	 * @return
	 */
	public int getKeySize() {
		return keySizeBytes;
	}

	/**
	 * Returns the {@link PositionManager} for this btree.
	 * 
	 * @return
	 */
	PositionManager getPositionManager() {
		return positionManager;
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

	private final LinkedList<NodeRef<T>> saveQueue = new LinkedList<NodeRef<T>>();

	void addToSaveQueue(NodeRef<T> node) {
		if (file.isPresent())
			saveQueue.push(node);
	}

	void load(NodeRef<T> node) {
		if (getFile().isPresent()) {
			try {

				RandomAccessFile f = new RandomAccessFile(getFile().get(), "r");
				f.seek(node.getPosition().get());
				int numBytes = nodeLengthBytes();
				byte[] b = new byte[numBytes];
				f.read(b);
				f.close();

				ByteArrayInputStream bytes = new ByteArrayInputStream(b);
				node.load(bytes);

			} catch (FileNotFoundException e) {
				throw new RuntimeException(e);
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		}
	}

	private int nodeLengthBytes() {
		return getDegree() * getKeySize();
	}

	private void writeBytes(RandomAccessFile f, ByteArrayOutputStream bytes)
			throws IOException {
		f.write(bytes.toByteArray());
	}

	public void displayFile() {
		try {
			System.out.println("------------ File contents ----------------");
			System.out.println(getFile().get());
			System.out.println("length=" + getFile().get().length());
			RandomAccessFile f = new RandomAccessFile(getFile().get(), "r");
			int pos = 0;
			while (pos < file.get().length()) {
				InputStream is = getStream(f, pos, nodeLengthBytes());
				NodeRef<T> ref = new NodeRef<T>(this, Optional.<Long> absent());
				NodeActual<T> node = new NodeActual<T>(this, ref);
				ref.load(is, node);
				displayNode(pos, node);
				pos += nodeLengthBytes();
			}
			System.out.println("------------");
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	private void displayNode(long pos, NodeActual<T> node) {
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

	private ByteArrayInputStream getStream(RandomAccessFile f, long pos,
			long numBytes) {
		try {
			f.seek(pos);
			byte[] bytes = new byte[(int) numBytes];
			f.read(bytes);
			return new ByteArrayInputStream(bytes);
		} catch (IOException e) {
			throw new RuntimeException(e);
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

}
