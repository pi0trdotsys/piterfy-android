package com.eddyizm.tempus.ui.fragment;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.media3.common.util.UnstableApi;
import androidx.viewpager2.widget.ViewPager2;

import com.eddyizm.tempus.R;
import com.eddyizm.tempus.databinding.FragmentHomeBinding;
import com.eddyizm.tempus.navigation.NavigationController;
import com.eddyizm.tempus.ui.activity.MainActivity;
import com.eddyizm.tempus.ui.fragment.pager.HomePager;
import com.eddyizm.tempus.util.Preferences;
import com.google.android.material.appbar.AppBarLayout;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;

import java.util.Objects;

@UnstableApi
public class HomeFragment extends Fragment {
    private static final String TAG = "HomeFragment";

    private FragmentHomeBinding bind;
    private MainActivity activity;
    private NavigationController navigationController;

    private MaterialToolbar materialToolbar;
    private AppBarLayout appBarLayout;
    private TabLayout tabLayout;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        activity = (MainActivity) getActivity();
        navigationController = activity.getNavigationController();
        bind = FragmentHomeBinding.inflate(inflater, container, false);
        return bind.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        initAppBar();
        initHomePager();
    }

    @Override
    public void onStart() {
        super.onStart();

        activity.toggleBottomNavigationBarVisibilityOnOrientationChange();
        activity.setBottomSheetVisibility(true);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        bind = null;
    }

    private void initAppBar() {
        appBarLayout = bind.getRoot().findViewById(R.id.toolbar_fragment);
        materialToolbar = bind.getRoot().findViewById(R.id.toolbar);

        activity.setSupportActionBar(materialToolbar);
        navigationController.setHamburgerMenuForLandscape(activity, materialToolbar);

        Objects.requireNonNull(materialToolbar.getOverflowIcon()).setTint(requireContext().getResources().getColor(R.color.titleTextColor, null));

        // Piterfy: zakladki jako chipsy w stylu Spotify (layout piterfy_chips_tabs)
        tabLayout = (TabLayout) getLayoutInflater().inflate(R.layout.piterfy_chips_tabs, appBarLayout, false);

        appBarLayout.addView(tabLayout);
    }

    /** Piterfy: odstepy chipsow (margines) i wewnetrzny padding tekstu - tekst ma byc na srodku pigulki. */
    private void styleChips() {
        if (tabLayout == null) return;
        float density = getResources().getDisplayMetrics().density;
        int sideMargin = Math.round(4 * density), verticalMargin = Math.round(6 * density);
        int sidePadding = Math.round(16 * density), verticalPadding = Math.round(8 * density);
        for (int i = 0; i < tabLayout.getTabCount(); i++) {
            TabLayout.Tab tab = tabLayout.getTabAt(i);
            if (tab == null) continue;
            android.view.ViewGroup.LayoutParams params = tab.view.getLayoutParams();
            if (params instanceof android.view.ViewGroup.MarginLayoutParams) {
                ((android.view.ViewGroup.MarginLayoutParams) params).setMargins(i == 0 ? Math.round(16 * density) : sideMargin, verticalMargin, sideMargin, verticalMargin);
                tab.view.setLayoutParams(params);
            }
            tab.view.setPaddingRelative(sidePadding, verticalPadding, sidePadding, verticalPadding);
            tab.view.setMinimumWidth(0);
        }
    }

    private void initHomePager() {
        HomePager pager = new HomePager(this);

        pager.addFragment(new HomeTabMusicFragment(), getString(R.string.home_section_music), R.drawable.ic_home);

        // Piterfy: podcasts are a first-class, always visible category.
        pager.addFragment(new HomeTabPiterfyPodcastFragment(), getString(R.string.home_section_podcast)  /* Piterfy: audycje z biblioteki zamiast RSS z Tempus */, R.drawable.ic_graphic_eq);

        if (Preferences.isRadioSectionVisible())
            pager.addFragment(new HomeTabRadioFragment(), getString(R.string.home_section_radio), R.drawable.ic_play_for_work);

        bind.homeViewPager.setAdapter(pager);
        bind.homeViewPager.setOffscreenPageLimit(3);
        bind.homeViewPager.setUserInputEnabled(false);

        new TabLayoutMediator(tabLayout, bind.homeViewPager,
                (tab, position) -> {
                    tab.setCustomView(R.layout.piterfy_chip_tab);  // Piterfy: tekst chipsa na srodku
                    tab.setText(pager.getPageTitle(position));
                    // tab.setIcon(pager.getPageIcon(position));
                }
        ).attach();

        styleChips();
        tabLayout.postDelayed(this::styleChips, 400);  // font wczytuje sie chwile - przelicz chipsy ponownie
        tabLayout.setVisibility(View.VISIBLE);
        tabLayout.setTabIndicatorFullWidth(false);
        tabLayout.setSelectedTabIndicatorColor(android.graphics.Color.TRANSPARENT);

        bind.homeViewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                refreshToolbarLibraryScope();
            }
        });
    }

    /**
     * The music library line in the toolbar belongs to the Music tab. Podcast and Radio share the
     * toolbar and are not filtered by library, so the line would name something with nothing to do
     * with what is on screen. Music is always the first page; the other two are optional.
     *
     * The toolbar reads this on every update instead of being pushed the page once, so a page the
     * pager put back on its own cannot leave the line naming the wrong tab.
     */
    public boolean isLibraryScopedTab() {
        return bind != null && bind.homeViewPager.getCurrentItem() == 0;
    }

    private void refreshToolbarLibraryScope() {
        ToolbarFragment toolbarFragment = (ToolbarFragment) getChildFragmentManager().findFragmentById(R.id.toolbar_fragment);
        if (toolbarFragment != null) toolbarFragment.refreshMusicLibraryIndicator();
    }
}
