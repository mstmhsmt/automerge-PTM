/**
 * Copyright (C) 2013-2014 Olaf Lessenich
 * Copyright (C) 2014-2017 University of Passau, Germany
 * <p>
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 * <p>
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 * <p>
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston,
 * MA 02110-1301  USA
 * <p>
 * Contributors:
 * Olaf Lessenich <lessenic@fim.uni-passau.de>
 * Georg Seibt <seibt@fim.uni-passau.de>
 */
package de.fosd.jdime.artifact.ast;

import de.fosd.jdime.Main;
import de.fosd.jdime.artifact.Artifact;
import de.fosd.jdime.artifact.ArtifactList;
import de.fosd.jdime.artifact.Artifacts;
import de.fosd.jdime.artifact.file.FileArtifact;
import de.fosd.jdime.config.JDimeConfig;
import de.fosd.jdime.config.merge.MergeContext;
import de.fosd.jdime.config.merge.MergeScenario;
import de.fosd.jdime.config.merge.Revision;
import de.fosd.jdime.execption.AbortException;
import de.fosd.jdime.matcher.Matcher;
import de.fosd.jdime.matcher.matching.Color;
import de.fosd.jdime.matcher.matching.Matchings;
import de.fosd.jdime.merge.Merge;
import de.fosd.jdime.operations.ConflictOperation;
import de.fosd.jdime.operations.MergeOperation;
import de.fosd.jdime.operations.Operation;
import de.fosd.jdime.stats.KeyEnums;
import de.fosd.jdime.stats.MergeScenarioStatistics;
import javafx.util.Pair;
import org.extendj.ast.*;
import synthesis.SynthesisContext;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import static de.fosd.jdime.config.CommandLineConfigSource.CLI_MAPPER_1;
import static de.fosd.jdime.config.CommandLineConfigSource.CLI_TOP_K;
import static de.fosd.jdime.strdump.DumpMode.PLAINTEXT_TREE;
import static de.fosd.jdime.util.SuccessLevel.SUCCESS;
import static java.util.logging.Level.SEVERE;

/**
 * @author Olaf Lessenich
 */
public class ASTNodeArtifact extends Artifact<ASTNodeArtifact> {

    private static final Logger LOG = Logger.getLogger(ASTNodeArtifact.class.getCanonicalName());

    /**
     * Parses the content of the given <code>FileArtifact</code> to an AST. If the <code>artifact</code> is empty,
     * an empty <code>ASTNode</code> obtained via {@link ASTNode#ASTNode()} will be returned.
     *
     * @param artifact the <code>FileArtifact</code> to parse
     * @return the root of the resulting AST
     */
    private static ASTNode<?> parse(FileArtifact artifact) {
        ASTNode<?> astNode;

        if (artifact.isEmpty()) {
            astNode = new ASTNode<>();
        } else {
            Program p = new Program();

            try {
                p.addSourceFile(artifact.getFile().getPath());
            } catch (IOException e) {
                throw new RuntimeException(e);
            }

            astNode = p;
        }

        return astNode;
    }

    /**
     * Encapsulated ASTNode.
     */
    protected ASTNode<?> astnode;

    /**
     * Constructs a new <code>ASTNodeArtifact</code> (tree) representing the AST of the code in <code>artifact</code>.
     * All members of the tree will be in the same <code>Revision</code> as <code>artifact</code>.
     *
     * @param artifact the <code>FileArtifact</code> containing the code to be parsed
     */
    public ASTNodeArtifact(FileArtifact artifact) {
        this(artifact.getRevision(), new AtomicInteger()::getAndIncrement, parse(artifact));
    }

    /**
     * Constructs a new <code>ASTNodeArtifact</code> encapsulating an empty <code>ASTNode</code> obtained via
     * {@link ASTNode#ASTNode()}.
     *
     * @param revision the <code>Revision</code> for this <code>ASTNodeArtifact</code>
     */
    protected ASTNodeArtifact(Revision revision) {
        this(revision, new AtomicInteger()::getAndIncrement, new ASTNode<>());
    }

    /**
     * Constructs a new <code>ASTNodeArtifact</code> encapsulating the given <code>ASTNode</code>. Children
     * <code>ASTNodeArtifact</code>s for all the children of <code>astNode</code> will be added.
     *
     * @param revision the <code>Revision</code> for this <code>ASTNodeArtifact</code>
     * @param astNode  the <code>ASTNode</code> to encapsulate
     */
    private ASTNodeArtifact(Revision revision, ASTNode<?> astNode) {
        this(revision, new AtomicInteger()::getAndIncrement, astNode);
    }

    /**
     * Constructs a new <code>ASTNodeArtifact</code> encapsulating the given <code>ASTNode</code>. Children
     * <code>ASTNodeArtifact</code>s for all the children of <code>astNode</code> will be added.
     *
     * @param revision the <code>Revision</code> for this <code>ASTNodeArtifact</code>
     * @param number   supplies first the number for this artifact and then in DFS order the number for its children
     * @param astNode  the <code>ASTNode</code> to encapsulate
     */
    private ASTNodeArtifact(Revision revision, Supplier<Integer> number, ASTNode<?> astNode) {
        super(revision, number.get());

        this.astnode = astNode;
        initializeChildren(number);
    }

    /**
     * Copies the given {@link Artifact}.
     *
     * @param toCopy to {@link Artifact} to copy
     * @see #copy()
     */
    protected ASTNodeArtifact(ASTNodeArtifact toCopy) {
        super(toCopy);

        try {
            this.astnode = toCopy.astnode.clone();
        } catch (CloneNotSupportedException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Adds {@code ASTNodeArtifact} children to this artifact encapsulating the children of the {@link #astnode}.
     *
     * @param number supplies the numbers for the added children
     */
    private void initializeChildren(Supplier<Integer> number) {
        List<ASTNodeArtifact> children = new ArtifactList<>();

        for (int i = 0; i < astnode.getNumChild(); i++) {
            ASTNodeArtifact child = new ASTNodeArtifact(getRevision(), number, astnode.getChild(i));

            child.setParent(this);
            children.add(child);
        }

        setChildren(children);
    }

    /**
     * Returns whether the {@link ASTNode} contained in this {@link ASTNodeArtifact}
     * requires a fixed number of children.
     *
     * @return true iff the {@link ASTNode} is not a dynamic one (like {@link org.extendj.ast.List})
     */
    private boolean hasFixedNumberOfChildren() {
        return astnode.requiresFixedNumChildren();
    }

    @Override
    protected ASTNodeArtifact self() {
        return this;
    }

    /**
     * Returns the encapsulated ExtendJ AST node.
     *
     * @return the encapsulated AST node
     */
    protected final ASTNode<?> getASTNode() {
        return astnode;
    }

    @Override
    public ASTNodeArtifact copy() {
        return new ASTNodeArtifact(this);
    }

    @Override
    public ASTNodeArtifact createEmptyArtifact(Revision revision) {
        return new ASTNodeArtifact(revision);
    }

    private static final String EMPTY_ARTIFACT_DUMP_STRING =
            new ASTNodeArtifact(new Revision("")).dumpString();

    /**
     * Check if this ASTNodeArtifact is an empty artifact, say, created by calling `createEmptyArtifact`.
     *
     * @return this is an empty artifact
     * @author paul
     */
    public boolean isEmptyArtifact() {
        return dumpString().equals(EMPTY_ARTIFACT_DUMP_STRING);
    }

    @Override
    public String prettyPrint() {
        assert (astnode != null);

        try {
            rebuildAST();
            astnode.flushTreeCache();
        } catch (AbortException e) {
            throw e;
        } catch (Exception e) {
            LOG.severe("Exception caught during prettyPrint(): " + e);
            LOG.log(Level.SEVERE, e.getMessage(), e);
        }

        LOG.finest(() -> Artifacts.root(this).dump(PLAINTEXT_TREE));

        return astnode.prettyPrint();
    }

    @Override
    public final boolean exists() {
        return astnode != null;
    }

    @Override
    public final String getId() {
        return getRevision() + ":" + getNumber();
    }

    @Override
    protected String hashId() {
        return astnode.getMatchingRepresentation();
    }

    @Override
    public KeyEnums.Type getType() {
        if (isMethod()) {
            return KeyEnums.Type.METHOD;
        } else if (isClass()) {
            return KeyEnums.Type.CLASS;
        } else if (astnode instanceof TryStmt) {
            return KeyEnums.Type.TRY;
        } else if (astnode instanceof Block) {
            return KeyEnums.Type.BLOCK;
        } else {
            return KeyEnums.Type.NODE;
        }
    }

    @Override
    public KeyEnums.Level getLevel() {
        KeyEnums.Type type = getType();

        if (type == KeyEnums.Type.METHOD) {
            return KeyEnums.Level.METHOD;
        } else if (type == KeyEnums.Type.CLASS) {
            return KeyEnums.Level.CLASS;
        } else {

            if (getParent() == null) {
                return KeyEnums.Level.TOP;
            } else {
                return getParent().getLevel();
            }
        }
    }

    @Override
    public void mergeOpStatistics(MergeScenarioStatistics mScenarioStatistics, MergeContext mergeContext) {
        mScenarioStatistics.getTypeStatistics(getRevision(), getType()).incrementNumMerged();
        mScenarioStatistics.getLevelStatistics(getRevision(), getLevel()).incrementNumMerged();
    }

    /**
     * Returns whether this <code>ASTNodeArtifact</code> represents a method declaration.
     *
     * @return true iff this is a method declaration
     */
    private boolean isMethod() {
        return astnode instanceof MethodDecl || astnode instanceof ConstructorDecl;
    }

    /**
     * Returns whether the <code>ASTNodeArtifact</code> is within a method.
     *
     * @return true iff the <code>ASTNodeArtifact</code> is within a method
     */
    public boolean isWithinMethod() {
        ASTNodeArtifact parent = getParent();
        return parent != null && (parent.isMethod() || parent.isWithinMethod());
    }

    /**
     * Returns whether this <code>ASTNodeArtifact</code> represents a class or interface declaration.
     *
     * @return true iff this is a class or method declaration
     */
    private boolean isClass() {
        return astnode instanceof ClassDecl || astnode instanceof InterfaceDecl;
    }

    /**
     * Optionally returns the enclosing class or method (or constructor) declaration artifact (whichever is closest)
     * of this {@link ASTNodeArtifact}.
     *
     * @return the enclosing class or method declaration an empty {@link Optional} if there is neither
     */
    public Optional<ASTNodeArtifact> enclosingClassOrMethod() {
        ASTNodeArtifact current = this;

        while (current != null) {
            KeyEnums.Type type = current.getType();

            if (type == KeyEnums.Type.METHOD || type == KeyEnums.Type.CLASS) {
                return Optional.of(current);
            }

            current = current.getParent();
        }

        return Optional.empty();
    }

    /**
     * Optionally returns the {@link ASTNodeArtifact} wrapping the given {@link ASTNode} from the
     * {@link ASTNodeArtifact} tree rooted in this {@link ASTNodeArtifact}.
     *
     * @param node the {@link ASTNode} whose corresponding {@link ASTNodeArtifact} is to be found
     * @return optionally the {@link ASTNodeArtifact} containing the given {@link ASTNode}
     */
    public Optional<ASTNodeArtifact> findWrappingASTNodeArtifact(ASTNode<?> node) {
        return Artifacts.dfsStream(this).filter(artifact -> artifact.astnode == node).findFirst();
    }

    @Override
    public Optional<Supplier<String>> getUniqueLabel() {
        boolean hasLabel = ImportDecl.class.isAssignableFrom(astnode.getClass())
                || Literal.class.isAssignableFrom(astnode.getClass());

        return hasLabel ? Optional.of(() -> astnode.getMatchingRepresentation()) : Optional.empty();
    }

    @Override
    public final boolean isEmpty() {
        return !hasChildren();
    }

    /**
     * Returns whether declaration order is significant for this node.
     *
     * @return whether declaration order is significant for this node
     */
    @Override
    public final boolean isOrdered() {
        return astnode.isOrdered();
    }

    /**
     * Returns whether a node matches another node.
     *
     * @param other node to compare with.
     * @return true if the node matches another node.
     */
    @Override
    public final boolean matches(final ASTNodeArtifact other) {
        assert (astnode != null);
        assert (other != null);
        assert (other.astnode != null);

        LOG.finest(() -> "match(" + getId() + ", " + other.getId() + ")");

        LOG.finest(() -> {
            String matchingRep = astnode.getMatchingRepresentation();
            String otherMatchingRep = other.astnode.getMatchingRepresentation();
            return String.format("Try Matching: {%s} and {%s}", matchingRep, otherMatchingRep);
        });

        return astnode.matches(other.astnode);
    }

    @Override
    public final boolean categoryMatches(ASTNodeArtifact other) {
        return astnode.getClass().equals(other.astnode.getClass());
    }

    @Override
    public void merge(MergeOperation<ASTNodeArtifact> operation, MergeContext context) {
        Objects.requireNonNull(operation, "operation must not be null!");
        Objects.requireNonNull(context, "context must not be null!");

        MergeScenario<ASTNodeArtifact> triple = operation.getMergeScenario();
        ASTNodeArtifact left = triple.getLeft();
        ASTNodeArtifact right = triple.getRight();
        ASTNodeArtifact target = operation.getTarget();

        boolean safeMerge = true;

        if (!isRoot() && hasFixedNumberOfChildren()) {

            // this language element has a fixed number of children, we need to be careful with this one
            // as it might cause lots of issues while being pretty-printed
            boolean leftChanges = !left.hasMatches();
            boolean rightChanges = !right.hasMatches();

            for (int i = 0; !leftChanges && i < left.getNumChildren(); i++) {
                leftChanges = !left.getChild(i).hasMatches();
            }

            for (int i = 0; !rightChanges && i < right.getNumChildren(); i++) {
                rightChanges = !right.getChild(i).hasMatches();
            }

            if (leftChanges && rightChanges) {
                // this one might be trouble

                if (left.getNumChildren() == right.getNumChildren()) {
                    // so far so good

                    for (int i = 0; i < left.getNumChildren(); i++) {
                        if (!left.getChild(i).astnode.getClass().getName().equals(right.getChild(i).astnode.getClass().getName())) {
                            // no good, this might get us some ClassCastExceptions
                            safeMerge = false;
                        }
                    }
                } else {
                    // no way ;)
                    safeMerge = false;
                }

            }
        }

        if (safeMerge) {
            Merge<ASTNodeArtifact> merge = new Merge<>();

            LOG.finest(() -> "Merging ASTs " + operation.getMergeScenario());
            merge.merge(operation, context);
        } else {
            LOG.finest(() -> String.format("Target %s expects a fixed amount of children.", target.getId()));
            LOG.finest(() -> String.format("Both %s and %s contain changes.", left.getId(), right.getId()));
            LOG.finest(() -> "We are scared of this node and report a conflict instead of performing the merge.");

            // to be safe, we will report a conflict instead of merging
            ASTNodeArtifact targetParent = target.getParent();
            targetParent.removeChild(target);

            Operation<ASTNodeArtifact> conflictOp = new ConflictOperation<>(left, right, targetParent,
                    left.getRevision().getName(), right.getRevision().getName(), base);
            conflictOp.apply(context);
        }
    }

    /**
     * Removes a child.
     *
     * @param child child that should be removed
     */
    private void removeChild(final ASTNodeArtifact child) {
        LOG.finest(() -> String.format("[%s] Removing child %s", getId(), child.getId()));
        LOG.finest(() -> String.format("Children before removal: %s", getChildren()));

        modifyChildren(children -> children.removeIf(it -> it == child));

        LOG.finest(() -> String.format("Children after removal: %s", getChildren()));
    }

    /**
     * Rebuild the encapsulated ASTNode tree top down. This should be only
     * called at the root node
     */
    private void rebuildAST() {
        LOG.finest(() -> String.format("%s.rebuildAST()", getId()));

        if (isConflict()) {
            astnode.isConflict = true;
            astnode.jdimeId = getId();

            if (base != null) {
                base.rebuildAST();
            } else {
                base = new ASTNodeArtifact(MergeScenario.BASE);
            }

            if (left != null) {
                left.rebuildAST();
                astnode.left = left.astnode;
            } else {
                /* FIXME: this is actually a bug.
                 * JDime should use an empty ASTNode with the correct revision information.
                 */
                left = new ASTNodeArtifact(MergeScenario.LEFT);
            }

            if (right != null) {
                right.rebuildAST();
                astnode.right = right.astnode;
            } else {
                /* FIXME: this is actually a bug.
                 * JDime should use an empty ASTNode with the correct revision information.
                 */
                right = new ASTNodeArtifact(MergeScenario.RIGHT);
            }
        }

        if (isChoice()) {
            astnode.isChoice = true;
            astnode.jdimeId = getId();
            astnode.variants = new LinkedHashMap<>();

            for (String condition : variants.keySet()) {
                ASTNodeArtifact variant = variants.get(condition);
                variant.rebuildAST();
                astnode.variants.put(condition, variant.astnode);
            }
        }

        ASTNode<?>[] newChildren = new ASTNode<?>[getNumChildren()];

        for (int i = 0; i < getNumChildren(); i++) {
            ASTNodeArtifact child = getChild(i);
            newChildren[i] = child.astnode;
            newChildren[i].setParent(astnode);
            child.rebuildAST();
        }

        astnode.jdimeId = getId();
        astnode.setChildren(newChildren);

        if (!isVirtual() && hasFixedNumberOfChildren() && getNumChildren() != astnode.getNumChildNoTransform()) {
            String msg = String.format("The %s requires a fixed number of children. JDime children: %d ExtendJ " +
                            "children: %d after AST rebuild. This is either a bug in ExtendJ or in JDime! " +
                            "Inspect AST element %s", astnode.getClass(), getNumChildren(),
                    astnode.getNumChildNoTransform(), getId());
            System.err.println(astnode.dumpTree());
            System.err.println(this.dump(PLAINTEXT_TREE));
            throw new AbortException(msg);
        }
    }

    @Override
    public final String toString() {
        return astnode.getMatchingRepresentation();
    }

    /**
     * Check if this ASTNode is of type `List`.
     *
     * @return whether it is of type `List`
     * @author paul
     */
    @Override
    public boolean isList() {
        return getASTNode() instanceof org.extendj.ast.List;
    }

    /**
     * Check if this ASTNode is of type `Block`.
     *
     * @return whether it is of type `Block`
     * @author paul
     */
    public boolean isBlock() {
        return getASTNode() instanceof org.extendj.ast.Block;
    }

    @Override
    public ASTNodeArtifact createConflictArtifact(ASTNodeArtifact left, ASTNodeArtifact right, ASTNodeArtifact base) {

        /*
         * The generated ASTNodeArtifact is virtual meaning it does not represent a part of the
         * original source code. As such the contained ASTNode should really be null. Since the
         * ASTNode has to be part of the ExtendJ AST, it can not be null and has to be of the correct type.
         * When pretty printing the AST, the ASTNode will be cast to the expected type and in its specific
         * prettyPrint() method the fact that it is virtual will be detected and handled by printing
         * conflict markers or ifdefs in the case of choice nodes.
         */
        ASTNode<?> typeNode;

        try {
            if (left != null) {
                typeNode = left.astnode.clone();
            } else {
                typeNode = right.astnode.clone();
            }
        } catch (CloneNotSupportedException e) {
            throw new RuntimeException("Failed to clone an ASTNode for a conflict artifact.", e);
        }

        ASTNodeArtifact conflict = new ASTNodeArtifact(MergeScenario.CONFLICT, typeNode);
        conflict.setConflict(left, right, base);

        return conflict;
    }

    @Override
    public ASTNodeArtifact createChoiceArtifact(String condition, ASTNodeArtifact artifact) {
        LOG.fine("Creating choice node");

        /*
         * See above in createConflictArtifact().
         */
        ASTNode<?> typeNode;

        try {
            typeNode = artifact.astnode.clone();
        } catch (CloneNotSupportedException e) {
            throw new RuntimeException("Failed to clone an ASTNode for a choice artifact.", e);
        }

        ASTNodeArtifact choice = new ASTNodeArtifact(MergeScenario.CHOICE, typeNode);
        choice.setChoice(condition, artifact);

        return choice;
    }

    /**
     * Check if the artifact has conflict.
     *
     * @return has conflict
     * @author paul
     */
    public boolean hasConflict() {
        if (isConflict()) {
            return true;
        }
        for (ASTNodeArtifact child : getChildren()) {
            if (child.hasConflict()) {
                return true;
            }
        }

        return false;
    }

    /**
     * Collect conflict nodes.
     *
     * @author paul
     */
    public List<ASTNodeArtifact> collectConflictNodes() {
        List<ASTNodeArtifact> nodes = new ArrayList<>();
        if (isConflict()) {
            nodes.add(this);
            return nodes;
        }

        int n = getNumChildren();
        for (int i = 0; i < n; i++) {
            nodes.addAll(getChild(i).collectConflictNodes());
        }

        return nodes;
    }

    /**
     * Print conflict (if this artifact has conflict).
     *
     * @author paul
     */
    public void printConflict() {
        System.out.println("--- left ---");
        System.out.println(left.prettyPrint());
        System.out.println("--- right---");
        System.out.println(right.prettyPrint());
        System.out.println("--- base ---");
        System.out.println(base.prettyPrint());
    }

    /**
     * Solve conflicts with synthesis.
     *
     * @param nodes    conflict nodes
     * @param context  merge context
     * @param scenario merge scenario
     */
    public void solveConflicts(List<ASTNodeArtifact> nodes,
                               MergeContext context, MergeScenario<FileArtifact> scenario) {
        JDimeConfig config = Main.config;
        int maxK = config.getInteger(CLI_TOP_K).orElse(32);

        Optional<FileArtifact> expFile = context.getExpected(scenario);
        if (expFile.isPresent()) {
            FileArtifact expected = expFile.get();
            ASTNodeArtifact expAST = new ASTNodeArtifact(expected);

            Matcher<ASTNodeArtifact> matcher = new Matcher<>(this, expAST);
            Matchings<ASTNodeArtifact> matchings = matcher.match(context, Color.GREEN);

            int num = 0;
            for (ASTNodeArtifact node : nodes) {
                num++;

                File holeFile = new File(expected.getFile().getAbsolutePath() + ".hole" + num);
                ASTNodeArtifact exp = null;
                if (holeFile.exists()) { // check using hole file
                    exp = new InternalASTNode(holeFile);
                    LOG.info("Synthesis: Check using hole file: " + holeFile);
                } else {
                    Optional<ASTNodeArtifact> expRootOpt = matchings.queryRightByLeftId(node.getParent().getId());
                    if (!expRootOpt.isPresent()) {
                        LOG.severe("Synthesis: Expected answer is missing in: " +
                                expected.getFile().getAbsolutePath());
                        System.out.println(node.getParent().dump(PLAINTEXT_TREE));
                        return;
                    }

                    ASTNodeArtifact expRoot = expRootOpt.get();
                    int index = node.getParent().indexOf(node);
                    if (index >= expRoot.getNumChildren()) {
                        LOG.severe("Synthesis: Expected child (#" + index + ") is missing in: " +
                                expected.getFile().getAbsolutePath());
                        System.out.println(expRoot.dump(PLAINTEXT_TREE));
                        System.out.println();
                        System.out.println(node.getParent().dump(PLAINTEXT_TREE));
                        return;
                    }

                    exp = node.isList() ?
                            expRoot.wrapListArtifact(index, node) :
                            expRoot.getChild(index);
                }

                assert exp != null;

                LOG.info("Synthesis: Expected");
                if (LOG.isLoggable(Level.INFO)) {
                    System.out.println(exp.prettyPrint());
                }

                int depth = 2;
                int k = 0;
                int totalSteps = 0;
                boolean found = false;

                long startTime = System.currentTimeMillis();

                if (node.left.isEmptyArtifact()) { // special case: left is empty
                    LOG.info("Synthesis: Left artifact is empty");

                    k = 1;
                    totalSteps = 1;
                    LOG.fine("Synthesis: Check 1: <empty>");

                    if (exp.eq(node.left)) {
                        found = true;
                    } else {
                        k = 2;
                        totalSteps = 2;
                        LOG.fine("Synthesis: Check 2:\n" + node.right.prettyPrint());

                        if (exp.eq(node.right)) {
                            found = true;
                        }
                    }
                } else if (node.right.isEmptyArtifact()) { // special case: right is empty
                    LOG.info("Synthesis: Right artifact is empty");

                    k = 1;
                    totalSteps = 1;
                    LOG.fine("Synthesis: Check 1: <empty>");

                    if (exp.eq(node.right)) {
                        found = true;
                    } else {
                        k = 2;
                        totalSteps = 2;
                        LOG.fine("Synthesis: Check 2:\n" + node.left.prettyPrint());

                        if (exp.eq(node.left)) {
                            found = true;
                        }
                    }
                } else { // normal case
                    while (depth < 5) {
                        SynthesisContext ctx = new SynthesisContext(node.left, node.right, node.base, LOG, depth);
                        Pair<Boolean, Integer> ret = ctx.check(exp, maxK);
                        found = ret.getKey();
                        k = ret.getValue();
                        totalSteps += k;
                        if (found) {
                            found = true;
                            break;
                        } else {
                            depth++;
                        }
                    }
                }

                long runtime = System.currentTimeMillis() - startTime;

                LOG.info("Synthesis: Searched depth: " + depth);
                LOG.info("Synthesis: Searched total steps: " + totalSteps);
                if (found) {
                    LOG.log(SUCCESS, "Synthesis: FOUND");
                } else {
                    LOG.log(SEVERE, "Synthesis: NOT FOUND");
                }
                LOG.info(String.format("Synthesis time: %d ms.", runtime));
            }
        } else {
            for (ASTNodeArtifact node : nodes) {
                SynthesisContext ctx = new SynthesisContext(node.left, node.right, node.base, LOG, 2);
                ctx.take(maxK);
            }
        }
    }

    /**
     * Take children starting at index `from` and form a new ASTNodeArtifact.
     *
     * @param from starting index
     * @param src  source artifact
     * @return the wrapped ASTNodeArtifact
     */
    public ASTNodeArtifact wrapListArtifact(int from, ASTNodeArtifact src) {
        ASTNodeArtifact dst = src.copy();
        dst.clearChildren();
        for (int i = from; i < getNumChildren(); i++) {
            dst.addChild(getChild(i));
        }

        return dst;
    }

    private boolean root = false;

    public void setRoot() {
        root = true;
    }

    public boolean isRoot() {
        return root;
    }

    public final boolean isLeaf() {
        return getChildren().isEmpty();
    }

    public final boolean isLeafNode() {
        if (astnode instanceof Dot) return true;
        if (astnode instanceof AssignSimpleExpr) return true;

        return isLeaf();
    }

    private String shortName() {
        return astnode.getClass().getName().substring(16);
    }

    /**
     * Get kind name.
     *
     * @return
     * @author paul
     */
    public String kind(String parentName) {
        // Special case: the method name of `MethodAccess` is excluded in children, instead,
        // we get it by calling `this.getId()`.
        if (astnode instanceof MethodAccess) {
            MethodAccess m = (MethodAccess) astnode;
            return shortName() + "." + m.getID();
        }

        if (Main.config.getBoolean(CLI_MAPPER_1).orElse(true)) {
            if (astnode instanceof Block || astnode instanceof org.extendj.ast.List) {
                return parentName + "." + shortName();
            }
        }

        return shortName();
    }

    public String[] abstractArgumentNames(String parentName) {
        if (isList()) {
            String[] names = new String[1];
            names[0] = kind(parentName) + ".arg.list";
            return names;
        }

        int n = getNumChildren();
        String[] names = new String[n];
        for (int i = 0; i < n; i++) {
            names[i] = kind(parentName) + ".arg" + i;
        }
        return names;
    }

    public boolean eq(ASTNodeArtifact that) {
        return astnode.dumpTree().equals(that.astnode.dumpTree()) ||
                astnode.prettyPrint().equals(that.astnode.prettyPrint());
    }

    public String dumpString() {
        return astnode.getClass().getName();
    }

    public String dumpTree() {
        return astnode.dumpTree();
    }
}
