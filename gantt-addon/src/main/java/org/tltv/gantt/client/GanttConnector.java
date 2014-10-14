/*
 * Copyright 2014 Tomi Virtanen
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.tltv.gantt.client;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.tltv.gantt.Gantt;
import org.tltv.gantt.client.shared.GanttClientRpc;
import org.tltv.gantt.client.shared.GanttServerRpc;
import org.tltv.gantt.client.shared.GanttState;

import com.google.gwt.core.client.GWT;
import com.google.gwt.core.client.Scheduler;
import com.google.gwt.core.client.Scheduler.ScheduledCommand;
import com.google.gwt.event.dom.client.ScrollEvent;
import com.google.gwt.event.dom.client.ScrollHandler;
import com.google.gwt.event.logical.shared.AttachEvent;
import com.google.gwt.event.logical.shared.AttachEvent.Handler;
import com.google.gwt.event.shared.HandlerRegistration;
import com.google.gwt.user.client.Timer;
import com.google.gwt.user.client.ui.Widget;
import com.vaadin.client.BrowserInfo;
import com.vaadin.client.ComponentConnector;
import com.vaadin.client.ConnectorHierarchyChangeEvent;
import com.vaadin.client.LocaleNotLoadedException;
import com.vaadin.client.LocaleService;
import com.vaadin.client.Util;
import com.vaadin.client.communication.RpcProxy;
import com.vaadin.client.communication.StateChangeEvent;
import com.vaadin.client.communication.StateChangeEvent.StateChangeHandler;
import com.vaadin.client.ui.AbstractHasComponentsConnector;
import com.vaadin.client.ui.FocusableScrollPanel;
import com.vaadin.client.ui.VScrollTable;
import com.vaadin.client.ui.layout.ElementResizeEvent;
import com.vaadin.client.ui.layout.ElementResizeListener;
import com.vaadin.client.ui.table.TableConnector;
import com.vaadin.shared.Connector;
import com.vaadin.shared.ui.Connect;

/**
 * Connector for client side GWT {@link GanttWidget} and server side
 * {@link Gantt} Vaadin component.
 * 
 * @author Tltv
 * 
 */
@Connect(Gantt.class)
public class GanttConnector extends AbstractHasComponentsConnector {

    GanttServerRpc rpc = RpcProxy.create(GanttServerRpc.class, this);

    String locale;
    long timeZoneOffset = 0;
    GanttDateTimeService dateTimeService;
    boolean notifyHeight = false;

    ComponentConnector delegateScrollConnector;
    FocusableScrollPanel delegateScrollPanelTarget;
    VScrollTable delegateScrollTableTarget;
    HandlerRegistration ganttScrollHandlerRegistration;
    HandlerRegistration scrollDelegateHandlerRegistration;
    // flag indicating that scroll is delegating right now
    boolean ganttDelegatingVerticalScroll = false;
    boolean delegatingVerticalScroll = false;

    Timer ganttScrollDelay = new Timer() {

        @Override
        public void run() {
            ganttDelegatingVerticalScroll = false;
        }
    };

    Timer scrollDelay = new Timer() {

        @Override
        public void run() {
            delegatingVerticalScroll = false;
        }
    };

    /**
     * Scroll handler for Gantt component to delegate to other component.
     */
    final ScrollHandler ganttScrollHandler = new ScrollHandler() {

        @Override
        public void onScroll(ScrollEvent event) {
            if (delegatingVerticalScroll) {
                // if other component is scrolling, don't allow this scroll
                // event
                return;
            }
            ganttScrollDelay.cancel();
            ganttDelegatingVerticalScroll = true;
            int scrollTop = getWidget().getScrollContainer().getScrollTop();
            try {
                delegateScrollPanelTarget.setScrollPosition(scrollTop);
            } finally {
                ganttScrollDelay.schedule(20);
            }
        }
    };
    /**
     * Scroll handler for scroll events from other component that Gantt may
     * react to.
     */
    final ScrollHandler scrollDelegateTargetHandler = new ScrollHandler() {

        @Override
        public void onScroll(ScrollEvent event) {
            if (ganttDelegatingVerticalScroll) {
                // if gantt is scrolling, don't allow this scroll event
                return;
            }
            scrollDelay.cancel();
            int scrollPosition = delegateScrollPanelTarget.getScrollPosition();
            delegatingVerticalScroll = true;
            try {
                getWidget().getScrollContainer().setScrollTop(scrollPosition);
            } finally {
                scrollDelay.schedule(20);
            }
        }
    };

    final StateChangeHandler scrollDelegateTargetStateChangeHandler = new StateChangeHandler() {

        @Override
        public void onStateChanged(StateChangeEvent stateChangeEvent) {
            Scheduler.get().scheduleDeferred(new ScheduledCommand() {

                @Override
                public void execute() {
                    adjustDelegateTargetHeightLazily();
                }
            });
        }
    };

    ElementResizeListener scrollDelegateTargetResizeListener = new ElementResizeListener() {

        @Override
        public void onElementResize(ElementResizeEvent e) {
            Scheduler.get().scheduleDeferred(new ScheduledCommand() {

                @Override
                public void execute() {
                    adjustDelegateTargetHeightLazily();
                }
            });
        }
    };

    Timer lazyAdjustDelegateTargetHeight = new Timer() {

        @Override
        public void run() {
            updateDelegateTargetHeight();
        }
    };

    LocaleDataProvider localeDataProvider = new LocaleDataProvider() {

        @Override
        public String[] getWeekdayNames() {
            try {
                return LocaleService.getDayNames(locale);
            } catch (LocaleNotLoadedException e) {
                GWT.log(e.getMessage(), e);
            }
            // return default
            return new String[] { "sunday", "monday", "tuesday", "wednesday",
                    "thursday", "friday", "saturday" };
        }

        @Override
        public String[] getMonthNames() {
            try {
                return LocaleService.getMonthNames(locale);
            } catch (LocaleNotLoadedException e) {
                GWT.log(e.getMessage(), e);
            }
            // return default
            return new String[] { "January", "February", "March", "April",
                    "May", "June", "July", "August", "September", "October",
                    "November", "December" };
        }

        @Override
        public int getFirstDayOfWeek() {
            try {
                // Gantt uses 1-based index, just as the server-side Java
                // Locale does. Vaadin locale state has 0-based value.
                return LocaleService.getFirstDayOfWeek(locale) + 1;
            } catch (LocaleNotLoadedException e) {
                GWT.log(e.getMessage(), e);
            }
            // return default
            return 1; // sunday
        }

        @Override
        public String formatDate(Date date, String formatStr) {
            if (dateTimeService == null) {
                try {
                    dateTimeService = new GanttDateTimeService(getLocale());
                } catch (LocaleNotLoadedException e) {
                    GWT.log("Could not create DateTimeService for the locale "
                            + getLocale(), e);
                    return "";
                }
            }
            return dateTimeService.formatDate(date, formatStr);
        }

        @Override
        public boolean isTwelveHourClock() {
            try {
                return LocaleService.isTwelveHourClock(locale);
            } catch (LocaleNotLoadedException e) {
                GWT.log(e.getMessage(), e);
            }
            return false;
        }

        @Override
        public String getLocale() {
            return locale;
        }

        @Override
        public long getTimeZoneOffset() {
            return timeZoneOffset;
        }

    };

    GanttRpc ganttRpc = new GanttRpc() {

        @Override
        public void stepClicked(int index) {
            rpc.stepClicked(index);
        }

        @Override
        public void onMove(int rowIndex, int newRowIndex, long startDate,
                long endDate) {
            rpc.onMove(rowIndex, newRowIndex, startDate, endDate);
        }

        @Override
        public void onResize(int rowIndex, long startDate, long endDate) {
            rpc.onResize(rowIndex, startDate, endDate);
        }
    };

    GanttClientRpc ganttClientRpc = new GanttClientRpc() {

        @Override
        public void updateDelegateTargetHeight() {
            GanttConnector.this.adjustDelegateTargetHeightLazily();
        }
    };

    int previousHeight = -1;
    int previousWidth = -1;

    final ElementResizeListener widgetResizeListener = new ElementResizeListener() {

        @Override
        public void onElementResize(ElementResizeEvent e) {
            final int height = e.getElement().getClientHeight();
            final int width = e.getElement().getClientWidth();
            if (previousHeight != height) {
                previousHeight = height;

                Scheduler.get().scheduleDeferred(new ScheduledCommand() {

                    @Override
                    public void execute() {
                        getWidget().notifyHeightChanged(height);
                        updateDelegateTargetHeight();
                    }
                });
            }
            if (previousWidth != width) {
                previousWidth = width;
                Scheduler.get().scheduleDeferred(new ScheduledCommand() {

                    @Override
                    public void execute() {
                        getWidget().notifyWidthChanged(width);
                        updateDelegateTargetHeight();
                    }
                });
            }
        }
    };

    public GanttConnector() {
        registerRpc(GanttClientRpc.class, ganttClientRpc);
    }

    @Override
    protected void init() {
        super.init();
        BrowserInfo info = BrowserInfo.get();
        getWidget().setBrowserInfo(info.isIE(), info.isIE8(), info.isIE9(),
                info.isChrome(), info.isSafari(), info.isWebkit());
        // if background grid is not needed, chrome and ie9 works without
        // setting alwaysCalculatePixelWidths flag to true.
        getWidget().setAlwaysCalculatePixelWidths(
                info.isChrome() || info.isSafari() || info.isOpera()
                        || info.isIE8() || info.isIE9());
        getWidget().setTouchSupported(info.isTouchDevice());
        getWidget().initWidget(ganttRpc, localeDataProvider);
        getLayoutManager().addElementResizeListener(getWidget().getElement(),
                widgetResizeListener);
    }

    @Override
    public void onUnregister() {
        getLayoutManager().removeElementResizeListener(
                getWidget().getElement(), widgetResizeListener);
        unRegisterScrollDelegateHandlers();
    }

    @Override
    protected Widget createWidget() {
        return GWT.create(GanttWidget.class);
    }

    @Override
    public GanttWidget getWidget() {
        return (GanttWidget) super.getWidget();
    }

    @Override
    public GanttState getState() {
        return (GanttState) super.getState();
    }

    @Override
    public void onStateChanged(StateChangeEvent stateChangeEvent) {
        super.onStateChanged(stateChangeEvent);

        locale = getState().locale;
        timeZoneOffset = (getState().timeZoneOffset != null ? getState().timeZoneOffset
                : 0);
        if (stateChangeEvent.hasPropertyChanged("locale")) {
            dateTimeService = null;
        }

        if (stateChangeEvent.hasPropertyChanged("monthRowVisible")
                || stateChangeEvent.hasPropertyChanged("yearRowVisible")
                || stateChangeEvent.hasPropertyChanged("monthFormat")
                || stateChangeEvent.hasPropertyChanged("yearFormat")
                || stateChangeEvent.hasPropertyChanged("weekFormat")
                || stateChangeEvent.hasPropertyChanged("dayFormat")) {
            notifyHeight = !stateChangeEvent.isInitialStateChange();
            getWidget().setForceUpdateTimeline();
        }
        if (!notifyHeight && stateChangeEvent.hasPropertyChanged("resolution")) {
            notifyHeight = !stateChangeEvent.isInitialStateChange();
        }

        if (stateChangeEvent.hasPropertyChanged("readOnly")) {
            getWidget().setMovableSteps(
                    !getState().readOnly && getState().movableSteps);
            getWidget().setResizableSteps(
                    !getState().readOnly && getState().resizableSteps);
        }

        if (stateChangeEvent.hasPropertyChanged("verticalScrollDelegateTarget")) {
            handleVerticalScrollDelegateTargetChange();
        }

        Scheduler.get().scheduleDeferred(new ScheduledCommand() {

            @Override
            public void execute() {
                getWidget().update(getSteps());
                if (notifyHeight) {
                    getWidget().notifyHeightChanged(previousHeight);
                }

                updateVerticalScrollDelegation();
                adjustDelegateTargetHeightLazily();
            }
        });
    }

    protected List<Widget> getSteps() {
        List<Widget> steps = new ArrayList<Widget>();
        for (Connector sc : getState().steps) {
            steps.add(((StepConnector) sc).getWidget());
        }
        return steps;
    }

    void handleVerticalScrollDelegateTargetChange() {
        Connector c = getState().verticalScrollDelegateTarget;
        unRegisterScrollDelegateHandlers();

        delegateScrollConnector = null;
        delegateScrollTableTarget = null;
        delegateScrollPanelTarget = null;
        if (c instanceof TableConnector) {
            delegateScrollConnector = (TableConnector) c;
            VScrollTable scrolltable = ((TableConnector) c).getWidget();
            delegateScrollTableTarget = scrolltable;
            delegateScrollPanelTarget = scrolltable.scrollBodyPanel;
            registerScrollDelegateHandlers();
        }
    }

    void unRegisterScrollDelegateHandlers() {
        if (scrollDelegateHandlerRegistration != null) {
            scrollDelegateHandlerRegistration.removeHandler();
        }
        if (ganttScrollHandlerRegistration != null) {
            ganttScrollHandlerRegistration.removeHandler();
        }
        if (delegateScrollConnector != null) {
            delegateScrollConnector
                    .removeStateChangeHandler(scrollDelegateTargetStateChangeHandler);
        }
        if (delegateScrollTableTarget != null) {
            getLayoutManager().removeElementResizeListener(
                    delegateScrollTableTarget.getElement(),
                    scrollDelegateTargetResizeListener);
        }
    }

    void registerScrollDelegateHandlers() {
        delegateScrollConnector
                .addStateChangeHandler(scrollDelegateTargetStateChangeHandler);
        getLayoutManager().addElementResizeListener(
                delegateScrollTableTarget.getElement(),
                scrollDelegateTargetResizeListener);
    }

    void updateVerticalScrollDelegation() {
        if (delegateScrollPanelTarget == null) {
            return; // scroll delegation is not set
        }
        // register scroll handler to Gantt widget
        ganttScrollHandlerRegistration = getWidget().addDomHandler(
                ganttScrollHandler, ScrollEvent.getType());

        // register a scroll handler to 'delegation' scroll panel.
        scrollDelegateHandlerRegistration = delegateScrollPanelTarget
                .addScrollHandler(scrollDelegateTargetHandler);

        // add detach listener to unregister scroll handler when its detached.
        delegateScrollPanelTarget.addAttachHandler(new Handler() {

            @Override
            public void onAttachOrDetach(AttachEvent event) {
                if (!event.isAttached()
                        && scrollDelegateHandlerRegistration != null) {
                    scrollDelegateHandlerRegistration.removeHandler();
                }
            }
        });
    }

    void updateDelegateTargetHeight() {
        if (delegateScrollTableTarget == null) {
            return;
        }

        int headerHeight = 0;
        if (delegateScrollTableTarget.tHead != null) {
            // update table header height to match the Gantt widget's header
            // height
            int border = Util
                    .measureVerticalBorder(delegateScrollTableTarget.tHead
                            .getElement());
            headerHeight = getWidget().getTimelineHeight();

            delegateScrollTableTarget.tHead.setHeight(Math.max(0, headerHeight
                    - border)
                    + "px");
        }

        int border = Util.measureVerticalBorder(delegateScrollPanelTarget
                .getElement());
        // Adjust table's scroll container height to match the Gantt widget's
        // scroll container height.
        int newTableScrollContainerHeight = getWidget()
                .getScrollContainerHeight();
        boolean tableHorScrollbarVisible = border >= Util
                .getNativeScrollbarSize();
        if (getWidget().isContentOverflowingHorizontally()) {
            getWidget().hideHorizontalScrollbarSpacer();
            if (tableHorScrollbarVisible) {
                newTableScrollContainerHeight += Util.getNativeScrollbarSize();
            }
        } else {
            if (tableHorScrollbarVisible) {
                getWidget().showHorizontalScrollbarSpacer();
            } else {
                getWidget().hideHorizontalScrollbarSpacer();
            }
        }

        delegateScrollPanelTarget.setHeight(Math.max(0,
                newTableScrollContainerHeight) + "px");

        getLayoutManager().setNeedsMeasure(
                (ComponentConnector) getState().verticalScrollDelegateTarget);
    }

    void adjustDelegateTargetHeightLazily() {
        lazyAdjustDelegateTargetHeight.cancel();
        // delay must be more than VScrollTable widget's lazy column adjusting.
        lazyAdjustDelegateTargetHeight.schedule(350);
    }

    @Override
    public void updateCaption(ComponentConnector connector) {
    }

    @Override
    public void onConnectorHierarchyChange(
            ConnectorHierarchyChangeEvent connectorHierarchyChangeEvent) {

        for (ComponentConnector c : connectorHierarchyChangeEvent
                .getOldChildren()) {
            if (!getChildComponents().contains(c)) {
                getWidget().removeStep(((StepConnector) c).getWidget());
            }
        }
    }

}
