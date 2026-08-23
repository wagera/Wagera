package com.astraupscale.app;

import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Kapali bir satir ve altina yapisan bir panelden olusan acilir bolum.
 *
 * <p>Satir kapaliyken bile gecerli degerini saginda tasir; boylece kullanici
 * paneli acmadan durumu okuyabilir. Ayni gruptaki bolumlerden ayni anda
 * yalnizca biri acik kalir — dort panel birden acilip sayfayi yeniden bir
 * kaydirma tuneline cevirmesin diye.
 */
final class Accordion {

    /** Chevron'un kapali ve acik acilari. */
    private static final float ANGLE_CLOSED = 0f;
    private static final float ANGLE_OPEN = 180f;
    private static final long TURN_MILLIS = 190L;

    private final View row;
    private final ViewGroup panel;
    private final TextView value;
    private final ImageView chevron;
    private final List<Accordion> group;

    private Accordion(View row, ViewGroup panel, TextView value, ImageView chevron,
                      List<Accordion> group) {
        this.row = row;
        this.panel = panel;
        this.value = value;
        this.chevron = chevron;
        this.group = group;
    }

    /**
     * Bir bolum kurar ve gruba ekler.
     *
     * @param group ayni anda yalnizca biri acik kalacak bolumler
     */
    static Accordion attach(View row, ViewGroup panel, TextView value, ImageView chevron,
                            final List<Accordion> group) {
        final Accordion a = new Accordion(row, panel, value, chevron, group);
        group.add(a);
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { a.toggle(); }
        });
        return a;
    }

    boolean isOpen() {
        return panel.getVisibility() == View.VISIBLE;
    }

    /** Satirin sagindaki ozet degeri yazar. */
    void setValue(CharSequence text) {
        value.setText(text);
        // Panel acikken deger satirda tekrar edilmez: goz iki kez okumasin.
        value.setVisibility(isOpen() ? View.GONE : View.VISIBLE);
    }

    void toggle() {
        if (isOpen()) {
            close();
        } else {
            for (Accordion other : group) {
                if (other != this) other.close();
            }
            open();
        }
    }

    private void open() {
        panel.setVisibility(View.VISIBLE);
        value.setVisibility(View.GONE);
        turn(ANGLE_OPEN);
    }

    void close() {
        if (!isOpen()) return;
        panel.setVisibility(View.GONE);
        value.setVisibility(View.VISIBLE);
        turn(ANGLE_CLOSED);
    }

    private void turn(float angle) {
        chevron.animate()
                .rotation(angle)
                .setDuration(Motion.scaled(TURN_MILLIS))
                .setInterpolator(new DecelerateInterpolator())
                .start();
    }

    /** Yeni bir bolum grubu. */
    static List<Accordion> newGroup() {
        return new ArrayList<>(4);
    }
}
