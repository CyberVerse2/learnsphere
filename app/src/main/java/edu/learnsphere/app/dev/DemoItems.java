package edu.learnsphere.app.dev;

import edu.learnsphere.core.error.InvalidInputException;
import edu.learnsphere.core.error.NotFoundException;
import edu.learnsphere.core.error.UserMessage;
import edu.learnsphere.core.events.ChangeType;
import edu.learnsphere.core.events.ItemsChanged;
import edu.learnsphere.core.model.Concept;
import edu.learnsphere.core.model.Flag;
import edu.learnsphere.core.model.FlagReason;
import edu.learnsphere.core.model.FlagSource;
import edu.learnsphere.core.model.ItemOrigin;
import edu.learnsphere.core.model.ItemStatus;
import edu.learnsphere.core.model.ItemType;
import edu.learnsphere.core.model.LearningItem;
import edu.learnsphere.core.model.SupportStatus;
import edu.learnsphere.core.service.FlagSummary;
import edu.learnsphere.core.service.ItemContent;
import edu.learnsphere.core.service.ItemCounts;
import edu.learnsphere.core.service.ItemDetail;
import edu.learnsphere.core.service.ItemFilter;
import edu.learnsphere.core.service.ItemService;
import edu.learnsphere.core.service.ItemSummary;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

/** {@link ItemService} over the {@link DemoStore}. */
final class DemoItems implements ItemService {

    private static final UserMessage PROMPT_NEEDED = UserMessage.of("Write the question before saving it.");
    private static final UserMessage ANSWER_NEEDED = UserMessage.of("Add the answer before saving the question.");

    private final DemoStore store;
    private final DemoProgress progress;

    DemoItems(DemoStore store, DemoProgress progress) {
        this.store = store;
        this.progress = progress;
    }

    @Override
    public List<ItemSummary> list(ItemFilter filter) {
        synchronized (store) {
            Stream<ItemSummary> stream = store.items.values().stream()
                    .filter(i -> filter.courseId() == null || i.courseId().equals(filter.courseId()))
                    .filter(i -> filter.moduleId() == null || filter.moduleId().equals(i.moduleId()))
                    .filter(i -> filter.statuses().isEmpty() || filter.statuses().contains(i.status()))
                    .filter(i -> filter.types().isEmpty() || filter.types().contains(i.type()))
                    .filter(i -> filter.support().isEmpty() || filter.support().contains(i.support()))
                    .filter(i -> filter.origin() == null || filter.origin() == i.origin())
                    .filter(i -> filter.conceptId() == null
                            || store.itemConcepts.getOrDefault(i.id(), Set.of()).contains(filter.conceptId()))
                    .filter(i -> filter.search() == null || filter.search().isBlank()
                            || i.prompt().toLowerCase(Locale.ROOT).contains(filter.search().toLowerCase(Locale.ROOT)))
                    .filter(i -> !filter.flaggedOnly() || openFlags(i.id()) > 0)
                    .map(this::summary)
                    .skip(Math.max(0, filter.offset()));
            return (filter.limit() > 0 ? stream.limit(filter.limit()) : stream).toList();
        }
    }

    private ItemSummary summary(LearningItem item) {
        DemoStore.Passage passage = store.passage(item.sourceChunkId());
        String document = passage == null || !store.documents.containsKey(passage.documentId()) ? null
                : store.documents.get(passage.documentId()).title();
        return new ItemSummary(item, store.conceptsOfItem(item.id()).stream().map(Concept::name).toList(),
                store.moduleTitle(item.moduleId()), document, openFlags(item.id()));
    }

    private int openFlags(String itemId) {
        return (int) store.flags.values().stream().filter(f -> f.itemId().equals(itemId) && f.resolvedAt() == null).count();
    }

    @Override
    public List<ItemDetail> reviewQueue(String courseId, String moduleId, int limit, int offset) {
        synchronized (store) {
            store.course(courseId);
            Stream<ItemDetail> stream = store.itemsOf(courseId).stream()
                    .filter(i -> i.status() == ItemStatus.DRAFT)
                    .filter(i -> moduleId == null || moduleId.equals(i.moduleId()))
                    .map(this::detail)
                    .skip(Math.max(0, offset));
            return (limit > 0 ? stream.limit(limit) : stream).toList();
        }
    }

    @Override
    public ItemCounts counts(String courseId) {
        synchronized (store) {
            List<LearningItem> items = store.itemsOf(courseId);
            return new ItemCounts(count(items, ItemStatus.DRAFT), count(items, ItemStatus.APPROVED),
                    count(items, ItemStatus.REJECTED), count(items, ItemStatus.RETIRED),
                    (int) items.stream().filter(i -> i.support() == SupportStatus.UNSUPPORTED).count(),
                    (int) items.stream().filter(i -> openFlags(i.id()) > 0).count());
        }
    }

    private static int count(List<LearningItem> items, ItemStatus status) {
        return (int) items.stream().filter(i -> i.status() == status).count();
    }

    @Override
    public ItemDetail get(String itemId) {
        synchronized (store) {
            return detail(store.item(itemId));
        }
    }

    private ItemDetail detail(LearningItem item) {
        List<Flag> flags = store.flags.values().stream().filter(f -> f.itemId().equals(item.id())).toList();
        return new ItemDetail(item, store.conceptsOfItem(item.id()), store.sourcePassage(item), flags,
                progress.stats(item));
    }

    @Override
    public LearningItem create(String courseId, String moduleId, ItemContent content) {
        validate(content);
        LearningItem item;
        synchronized (store) {
            store.course(courseId);
            long now = store.now();
            item = new LearningItem(DemoStore.newId(), courseId, moduleId, content.type(), content.prompt().strip(),
                    content.answer().strip(), content.explanation(), content.distractors(), content.difficulty(), null,
                    null, ItemOrigin.MANUAL, ItemStatus.APPROVED, SupportStatus.UNCHECKED, null, now, now);
            store.items.put(item.id(), item);
            store.itemConcepts.put(item.id(), new LinkedHashSet<>(content.conceptIds()));
        }
        changed(item, ChangeType.CREATED);
        return item;
    }

    @Override
    public LearningItem edit(String itemId, ItemContent content) {
        validate(content);
        LearningItem item = replace(itemId, i -> new LearningItem(i.id(), i.courseId(), i.moduleId(), content.type(),
                content.prompt().strip(), content.answer().strip(), content.explanation(), content.distractors(),
                content.difficulty(), i.sourceChunkId(), i.sourcePage(), i.origin(), i.status(), i.support(),
                i.supportNote(), i.createdAt(), store.now()));
        synchronized (store) {
            store.itemConcepts.put(itemId, new LinkedHashSet<>(content.conceptIds()));
        }
        return item;
    }

    private static void validate(ItemContent content) {
        if (content.prompt() == null || content.prompt().isBlank()) {
            throw new InvalidInputException(PROMPT_NEEDED, "blank prompt");
        }
        if (content.answer() == null || content.answer().isBlank()) {
            throw new InvalidInputException(ANSWER_NEEDED, "blank answer");
        }
        if (content.type() == ItemType.CLOZE && !content.prompt().contains(ItemType.CLOZE_BLANK)) {
            throw new InvalidInputException(UserMessage.of("Mark the missing word with ____ in the sentence."),
                    "cloze without blank");
        }
    }

    @Override
    public void moveToModule(String itemId, String moduleId) {
        replace(itemId, i -> new LearningItem(i.id(), i.courseId(), moduleId, i.type(), i.prompt(), i.answer(),
                i.explanation(), i.distractors(), i.difficulty(), i.sourceChunkId(), i.sourcePage(), i.origin(),
                i.status(), i.support(), i.supportNote(), i.createdAt(), store.now()));
    }

    @Override
    public void approve(String itemId) {
        setStatus(itemId, ItemStatus.APPROVED);
    }

    @Override
    public int approveAll(Collection<String> itemIds) {
        int count = 0;
        for (String id : itemIds) {
            setStatus(id, ItemStatus.APPROVED);
            count++;
        }
        return count;
    }

    @Override
    public void reject(String itemId) {
        setStatus(itemId, ItemStatus.REJECTED);
    }

    @Override
    public void retire(String itemId) {
        setStatus(itemId, ItemStatus.RETIRED);
    }

    @Override
    public void returnToDraft(String itemId) {
        setStatus(itemId, ItemStatus.DRAFT);
    }

    private void setStatus(String itemId, ItemStatus status) {
        replace(itemId, i -> new LearningItem(i.id(), i.courseId(), i.moduleId(), i.type(), i.prompt(), i.answer(),
                i.explanation(), i.distractors(), i.difficulty(), i.sourceChunkId(), i.sourcePage(), i.origin(), status,
                i.support(), i.supportNote(), i.createdAt(), store.now()));
    }

    @Override
    public void delete(String itemId) {
        LearningItem item;
        synchronized (store) {
            item = store.item(itemId);
            store.items.remove(itemId);
            store.reviews.remove(itemId);
            store.itemConcepts.remove(itemId);
        }
        changed(item, ChangeType.DELETED);
    }

    @Override
    public Flag addFlag(String itemId, FlagReason reason, String note) {
        Flag flag;
        LearningItem item;
        synchronized (store) {
            item = store.item(itemId);
            long now = store.now();
            flag = new Flag(DemoStore.newId(), itemId, reason, note, FlagSource.SELF, null, now, now);
            store.flags.put(flag.id(), flag);
        }
        changed(item, ChangeType.UPDATED);
        return flag;
    }

    @Override
    public List<FlagSummary> flags(String courseId, boolean includeResolved) {
        synchronized (store) {
            return store.flags.values().stream()
                    .filter(f -> includeResolved || f.resolvedAt() == null)
                    .filter(f -> store.items.containsKey(f.itemId()) && store.items.get(f.itemId()).courseId().equals(courseId))
                    .map(f -> {
                        LearningItem item = store.items.get(f.itemId());
                        return new FlagSummary(f, item.id(), item.type(), item.prompt(), store.moduleTitle(item.moduleId()));
                    })
                    .toList();
        }
    }

    @Override
    public void resolveFlag(String flagId) {
        LearningItem item;
        synchronized (store) {
            Flag f = store.flags.get(flagId);
            if (f == null) {
                throw new NotFoundException(UserMessage.NOT_AVAILABLE, "No flag " + flagId);
            }
            long now = store.now();
            store.flags.put(flagId, new Flag(f.id(), f.itemId(), f.reason(), f.note(), f.source(), now, f.createdAt(), now));
            item = store.items.get(f.itemId());
        }
        if (item != null) {
            changed(item, ChangeType.UPDATED);
        }
    }

    private LearningItem replace(String itemId, UnaryOperator<LearningItem> change) {
        LearningItem next;
        synchronized (store) {
            next = change.apply(store.item(itemId));
            store.items.put(itemId, next);
        }
        changed(next, ChangeType.UPDATED);
        return next;
    }

    private void changed(LearningItem item, ChangeType type) {
        store.events.publish(new ItemsChanged(item.courseId(), item.moduleId(), Set.of(item.id()), type));
    }
}
