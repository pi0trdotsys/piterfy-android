package com.eddyizm.tempus.ui.adapter;

import android.content.Context;
import android.graphics.Outline;
import android.os.Parcelable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.eddyizm.tempus.R;
import com.eddyizm.tempus.glide.CustomGlideRequest;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

/**
 * Piterfy TV: ekran glowny jako pionowa lista rzedow kart (naglowek + rzedy poziome), zaprojektowana pod
 * pilota: duze cele fokusu, skalowanie fokusowanej karty i informacja o fokusie dla tla w kolorze okladki.
 */
public class TvRowsAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    private static final int TYPE_HEADER = 0;
    private static final int TYPE_ROW = 1;

    /** Jedna karta: okladka + dwa wiersze tekstu + akcje pilota (OK / przytrzymaj OK). */
    public static final class Card {
        public final String title;
        public final String subtitle;
        public final String coverId;
        public final CustomGlideRequest.ResourceType coverType;
        public final boolean round;
        public final Runnable onClick;
        @Nullable public final Runnable onLongClick;

        public Card(String title, String subtitle, String coverId, CustomGlideRequest.ResourceType coverType,
                    boolean round, Runnable onClick, @Nullable Runnable onLongClick) {
            this.title = title == null ? "" : title;
            this.subtitle = subtitle == null ? "" : subtitle;
            this.coverId = coverId;
            this.coverType = coverType;
            this.round = round;
            this.onClick = onClick;
            this.onLongClick = onLongClick;
        }
    }

    /** Rzad kart. Adapter kart i pozycja przewiniecia zyja razem z rzedem, nie z recyklowanym widokiem. */
    public static final class Row {
        public final String key;
        public String title;
        public List<Card> cards = Collections.emptyList();
        final CardAdapter adapter = new CardAdapter();
        Parcelable scrollState;

        public Row(String key, String title) {
            this.key = key;
            this.title = title;
        }
    }

    public interface Listener {
        /** Karta dostala fokus - ekran moze np. zmienic gradient tla na kolor jej okladki. */
        void onCardFocused(Card card);
    }

    private final List<Row> rows = new ArrayList<>();
    private final Consumer<View> headerBinder;
    private final Listener listener;
    private final RecyclerView.RecycledViewPool cardPool = new RecyclerView.RecycledViewPool();

    public TvRowsAdapter(Consumer<View> headerBinder, Listener listener) {
        this.headerBinder = headerBinder;
        this.listener = listener;
        cardPool.setMaxRecycledViews(0, 24);
    }

    /** Podmienia widoczne rzedy (puste sa pomijane przez wywolujacego). */
    public void setRows(List<Row> visibleRows) {
        rows.clear();
        rows.addAll(visibleRows);
        notifyDataSetChanged();
    }

    @Override
    public int getItemViewType(int position) {
        return position == 0 ? TYPE_HEADER : TYPE_ROW;
    }

    @Override
    public int getItemCount() {
        return rows.size() + 1;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == TYPE_HEADER) {
            return new RecyclerView.ViewHolder(inflater.inflate(R.layout.piterfy_tv_home_header, parent, false)) { };
        }
        RowHolder holder = new RowHolder(inflater.inflate(R.layout.piterfy_tv_row, parent, false));
        holder.cards.setRecycledViewPool(cardPool);
        return holder;
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        if (position == 0) {
            headerBinder.accept(holder.itemView);
            return;
        }
        Row row = rows.get(position - 1);
        RowHolder rowHolder = (RowHolder) holder;
        rowHolder.title.setText(row.title);
        row.adapter.setCards(row.cards, listener);
        rowHolder.cards.setAdapter(row.adapter);
        if (row.scrollState != null) rowHolder.cards.getLayoutManager().onRestoreInstanceState(row.scrollState);
        rowHolder.row = row;
    }

    @Override
    public void onViewRecycled(@NonNull RecyclerView.ViewHolder holder) {
        if (holder instanceof RowHolder rowHolder && rowHolder.row != null) {
            rowHolder.row.scrollState = rowHolder.cards.getLayoutManager().onSaveInstanceState();
            rowHolder.row = null;
        }
        super.onViewRecycled(holder);
    }

    private static final class RowHolder extends RecyclerView.ViewHolder {
        final TextView title;
        final RecyclerView cards;
        Row row;

        RowHolder(@NonNull View itemView) {
            super(itemView);
            title = itemView.findViewById(R.id.tv_row_title);
            cards = itemView.findViewById(R.id.tv_row_cards);
            cards.setLayoutManager(new LinearLayoutManager(itemView.getContext(), LinearLayoutManager.HORIZONTAL, false));
            cards.setItemViewCacheSize(4);
        }
    }

    /** Poziomy rzad kart z animacja fokusu. */
    static final class CardAdapter extends RecyclerView.Adapter<CardAdapter.CardHolder> {
        private List<Card> cards = Collections.emptyList();
        private Listener listener;

        void setCards(List<Card> newCards, Listener newListener) {
            listener = newListener;
            if (cards == newCards) return;
            cards = newCards;
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public CardHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new CardHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.piterfy_tv_card, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull CardHolder holder, int position) {
            holder.bind(cards.get(position), listener);
        }

        @Override
        public int getItemCount() {
            return cards.size();
        }

        static final class CardHolder extends RecyclerView.ViewHolder {
            private static final ViewOutlineProvider OVAL = new ViewOutlineProvider() {
                @Override public void getOutline(View view, Outline outline) {
                    outline.setOval(0, 0, view.getWidth(), view.getHeight());
                }
            };
            private final View frame;
            private final ImageView cover;
            private final TextView title;
            private final TextView subtitle;

            CardHolder(@NonNull View itemView) {
                super(itemView);
                frame = itemView.findViewById(R.id.tv_card_frame);
                cover = itemView.findViewById(R.id.tv_card_cover);
                title = itemView.findViewById(R.id.tv_card_title);
                subtitle = itemView.findViewById(R.id.tv_card_subtitle);
            }

            void bind(Card card, Listener listener) {
                Context context = itemView.getContext();
                title.setText(card.title);
                subtitle.setText(card.subtitle);
                subtitle.setVisibility(card.subtitle.isEmpty() ? View.GONE : View.VISIBLE);

                cover.setOutlineProvider(card.round ? OVAL : ViewOutlineProvider.BACKGROUND);
                cover.setClipToOutline(true);
                frame.setForeground(ContextCompat.getDrawable(context,
                        card.round ? R.drawable.piterfy_tv_card_focus_oval : R.drawable.piterfy_tv_card_focus));
                com.bumptech.glide.RequestBuilder<android.graphics.drawable.Drawable> request =
                        CustomGlideRequest.Builder.from(context, card.coverId, card.coverType).build();
                // Okragla karta: bez przenikania, bo placeholder zostaje widoczny jako kwadrat za przezroczystymi rogami.
                (card.round ? request.circleCrop().dontAnimate() : request).into(cover);

                itemView.setOnClickListener(v -> card.onClick.run());
                itemView.setOnLongClickListener(card.onLongClick == null ? null : v -> {
                    card.onLongClick.run();
                    return true;
                });
                itemView.setScaleX(1f);
                itemView.setScaleY(1f);
                itemView.setTranslationZ(0f);
                title.setSelected(false);
                itemView.setOnFocusChangeListener((v, hasFocus) -> {
                    v.animate().cancel();
                    v.animate().scaleX(hasFocus ? 1.08f : 1f).scaleY(hasFocus ? 1.08f : 1f)
                            .translationZ(hasFocus ? 12f : 0f).setDuration(hasFocus ? 160L : 110L).start();
                    title.setSelected(hasFocus); // marquee tylko na fokusowanej karcie
                    if (hasFocus && listener != null) listener.onCardFocused(card);
                });
            }
        }
    }
}
