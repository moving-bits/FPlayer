package net.movingbits.fplayer;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.text.Editable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.core.graphics.ColorUtils;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

/** The profile popup (list of profiles) and the form to create or edit a profile. */
final class ProfileDialogs {

    /** Actions that need the activity: switching the playback over to another profile. */
    interface Host {
        /** Saves the playback state of the running playlist in its profile. */
        void savePlaybackState();

        /**
         * The active profile was changed in the store (selected, created or deleted); load its
         * settings.
         *
         * @param continuePlayback keep playing (at the same position) if playing right now
         */
        void onActiveProfileChanged(boolean continuePlayback);

        /** Name or color of a profile changed. */
        void onProfileEdited();
    }

    private final Context context;
    private final ProfileStore store;
    private final Host host;

    ProfileDialogs(final Context context, final ProfileStore store, final Host host) {
        this.context = context;
        this.store = store;
        this.host = host;
    }

    /** The color of the icon on a profile color: black on light, white on dark colors. */
    static int iconColorOn(final int background) {
        return ColorUtils.calculateLuminance(background) > 0.45 ? 0xFF000000 : 0xFFFFFFFF;
    }

    /** Colors a person icon on a circle (title bar button, list rows, swatches). */
    static void paintProfileIcon(final ImageView icon, final int colorIndex) {
        final int color = ProfileStore.COLORS[colorIndex];
        icon.setBackgroundTintList(ColorStateList.valueOf(color));
        icon.setImageTintList(ColorStateList.valueOf(iconColorOn(color)));
    }

    // ----- Profile popup -----

    void showProfiles() {
        host.savePlaybackState();
        final View content = LayoutInflater.from(context).inflate(R.layout.dialog_profiles, null);
        final RecyclerView list = content.findViewById(R.id.profile_list);
        final AlertDialog dialog = new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.profiles_title)
                .setView(content)
                .setPositiveButton(R.string.ok, null)
                .setNeutralButton(R.string.profile_new, null)
                .create();
        final ProfileAdapter adapter = new ProfileAdapter(dialog);
        list.setLayoutManager(new LinearLayoutManager(context));
        list.setAdapter(adapter);
        dialog.setOnShowListener(d ->
                // "Neu" must not close the popup
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> showCreate(dialog)));
        dialog.show();
    }

    private void confirmDelete(final Profile profile, final ProfileAdapter adapter) {
        new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.profile_delete_title)
                .setMessage(context.getString(R.string.profile_delete_message, profile.name))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.delete, (d, w) -> {
                    host.savePlaybackState();
                    if (store.delete(profile.id)) {
                        host.onActiveProfileChanged(false);
                    }
                    adapter.reload();
                })
                .show();
    }

    private final class ProfileAdapter extends RecyclerView.Adapter<ProfileAdapter.Holder> {

        private final AlertDialog dialog;
        private List<Profile> items;

        ProfileAdapter(final AlertDialog dialog) {
            this.dialog = dialog;
            items = store.getSorted();
        }

        @SuppressWarnings("NotifyDataSetChanged")
        void reload() {
            items = store.getSorted();
            notifyDataSetChanged();
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull final ViewGroup parent, final int viewType) {
            return new Holder(LayoutInflater.from(context).inflate(R.layout.item_profile, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull final Holder h, final int position) {
            final Profile profile = items.get(position);
            final boolean active = profile.id.equals(store.getActive().id);
            paintProfileIcon(h.color, profile.color);
            h.name.setText(profile.name);
            h.name.setTypeface(null, active ? Typeface.BOLD : Typeface.NORMAL);
            h.delete.setVisibility(profile.isDefault() ? View.INVISIBLE : View.VISIBLE);
            h.row.setOnClickListener(v -> {
                if (!active) {
                    host.savePlaybackState();
                    store.activate(profile.id);
                    host.onActiveProfileChanged(false);
                }
                dialog.dismiss();
            });
            h.edit.setOnClickListener(v -> showEdit(profile, this));
            h.delete.setOnClickListener(v -> confirmDelete(profile, this));
        }

        final class Holder extends RecyclerView.ViewHolder {
            final View row;
            final ImageView color;
            final TextView name;
            final View edit;
            final View delete;

            Holder(final View view) {
                super(view);
                row = view.findViewById(R.id.profile_row);
                color = view.findViewById(R.id.profile_color);
                name = view.findViewById(R.id.profile_name);
                edit = view.findViewById(R.id.profile_edit);
                delete = view.findViewById(R.id.profile_delete);
            }
        }
    }

    // ----- Create / edit form -----

    private void showCreate(final AlertDialog popup) {
        showForm(R.string.profile_new_title, store.suggestName(context.getString(R.string.profile_name_pattern)),
                store.suggestColor(), null, (name, color) -> {
                    host.savePlaybackState();
                    store.createFromActive(name, color);
                    host.onActiveProfileChanged(true);
                    popup.dismiss();
                });
    }

    private void showEdit(final Profile profile, final ProfileAdapter adapter) {
        showForm(R.string.profile_edit_title, profile.name, profile.color, profile.id, (name, color) -> {
            store.rename(profile.id, name, color);
            adapter.reload();
            host.onProfileEdited();
        });
    }

    private interface FormResult {
        void accept(String name, int color);
    }

    /**
     * Shows the form. {@code profileId} is the edited profile (to allow keeping its name) or
     * {@code null} for a new one. Closing via "Abbrechen" returns to the popup.
     */
    private void showForm(final int title, final String name, final int color, final String profileId,
                          final FormResult result) {
        final View content = LayoutInflater.from(context).inflate(R.layout.dialog_profile_edit, null);
        final TextInputLayout nameLayout = content.findViewById(R.id.profile_name_layout);
        final TextInputEditText nameInput = content.findViewById(R.id.profile_name_input);
        final GridLayout swatches = content.findViewById(R.id.profile_swatches);
        nameInput.setText(name);
        nameInput.selectAll();
        final int[] selectedColor = {color};
        final List<ImageView> swatchViews = new ArrayList<>();
        final int size = context.getResources().getDimensionPixelSize(R.dimen.profile_swatch_size);
        final int margin = context.getResources().getDimensionPixelSize(R.dimen.profile_swatch_margin);
        for (int i = 0; i < ProfileStore.COLORS.length; i++) {
            final int index = i;
            final ImageView swatch = new ImageView(context);
            final GridLayout.LayoutParams params = new GridLayout.LayoutParams();
            params.width = size;
            params.height = size;
            params.setMargins(margin, margin, margin, margin);
            swatch.setLayoutParams(params);
            swatch.setBackgroundResource(R.drawable.bg_profile_circle);
            swatch.setScaleType(ImageView.ScaleType.CENTER);
            swatch.setContentDescription(context.getString(R.string.profile_color_item, i + 1));
            swatch.setOnClickListener(v -> {
                selectedColor[0] = index;
                updateSwatches(swatchViews, index);
            });
            swatchViews.add(swatch);
            swatches.addView(swatch);
        }
        updateSwatches(swatchViews, color);

        final AlertDialog dialog = new MaterialAlertDialogBuilder(context)
                .setTitle(title)
                .setView(content)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.ok, null)
                .create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            final Editable text = nameInput.getText();
            final String entered = text != null ? text.toString().trim() : "";
            if (entered.isEmpty()) {
                nameLayout.setError(context.getString(R.string.profile_name_empty));
            } else if (store.isNameTaken(entered, profileId)) {
                nameLayout.setError(context.getString(R.string.profile_name_taken));
            } else {
                dialog.dismiss();
                result.accept(entered, selectedColor[0]);
            }
        }));
        dialog.show();
    }

    /** Shows the person icon (and a ring) on the selected swatch only. */
    private static void updateSwatches(final List<ImageView> swatches, final int selected) {
        for (int i = 0; i < swatches.size(); i++) {
            final ImageView swatch = swatches.get(i);
            final int color = ProfileStore.COLORS[i];
            swatch.setBackgroundTintList(ColorStateList.valueOf(color));
            if (i == selected) {
                swatch.setImageResource(R.drawable.ic_person);
                swatch.setImageTintList(ColorStateList.valueOf(iconColorOn(color)));
                swatch.setForeground(AppCompatResources.getDrawable(swatch.getContext(), R.drawable.bg_swatch_selected));
            } else {
                swatch.setImageDrawable(null);
                swatch.setForeground(null);
            }
            swatch.setSelected(i == selected);
        }
    }
}
