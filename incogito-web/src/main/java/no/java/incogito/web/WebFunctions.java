package no.java.incogito.web;

import fj.F;
import fj.F2;
import fj.F3;
import static fj.Function.curry;
import fj.P;
import fj.P2;
import fj.P1;
import fj.pre.Ord;
import fj.data.List;
import static fj.data.List.list;
import fj.data.Stream;
import fj.data.Set;
import fj.data.TreeMap;
import static fj.data.Set.empty;
import static fj.data.Stream.stream;
import no.java.incogito.Functions;
import no.java.incogito.application.IncogitoConfiguration;
import no.java.incogito.web.resources.XmlFunctions;
import no.java.incogito.web.servlet.WebCalendar;
import no.java.incogito.dto.SessionXml;
import no.java.incogito.domain.CssConfiguration;
import no.java.incogito.domain.Room;
import no.java.incogito.domain.Session;
import no.java.incogito.domain.Schedule;
import no.java.incogito.domain.SessionId;
import no.java.incogito.domain.UserSessionAssociation;
import no.java.incogito.domain.Event;
import no.java.incogito.domain.Level;

import javax.ws.rs.core.UriBuilder;
import java.text.NumberFormat;
import java.util.Map;
import java.util.Collection;
import java.util.HashMap;

import org.joda.time.LocalDate;

/**
 * @author <a href="mailto:trygvis@java.no">Trygve Laugst&oslash;l</a>
 * @version $Id$
 */
public class WebFunctions {

    private static final NumberFormat oneDigitFormat;

    static {
        oneDigitFormat = NumberFormat.getNumberInstance();
        oneDigitFormat.setMaximumFractionDigits(1);
        oneDigitFormat.setMinimumFractionDigits(1);
    }

    // -----------------------------------------------------------------------
    // Calendar CSS
    // -----------------------------------------------------------------------

    public static final F<CssConfiguration, F<List<Room>, List<String>>> generateCalendarCss = curry(new F2<CssConfiguration, List<Room>, List<String>>() {
        public List<String> f(CssConfiguration cssConfiguration, List<Room> roomList) {
            List<String> rooms = roomList.zipIndex().map(P2.tuple(roomToCss.f(cssConfiguration)));

            List<String> sessions = Functions.List_product(list("09", "10", "11", "12", "13", "14", "15", "16", "17", "18", "19"),
                    list("00", "15", "30", "45"), P.<String, String>p2()).
                    zipIndex().
                    map(hourToSessionCss.f(cssConfiguration));

            Stream<String> durations = stream(15, 60).zapp(Stream.repeat(durationToCss.f(cssConfiguration)));

            return List.join(list(rooms, sessions, durations.toList()));
        }
    });

    // div.room.r1 { left: 0; position: absolute; }
    public static final F<CssConfiguration, F<Room, F<Integer, String>>> roomToCss = curry(new F3<CssConfiguration, Room, Integer, String>() {
        public String f(CssConfiguration cssConfiguration, Room room, Integer integer) {
            return ".room" + integer + " {left: " + (integer * cssConfiguration.emPerRoom) + "em; position: absolute;}";
        }
    });

    // div.room.r1 { left: 0; position: absolute; }
    public static final F<CssConfiguration, F<Integer, String>> durationToCss = curry(new F2<CssConfiguration, Integer, String>() {
        public String f(CssConfiguration cssConfiguration, Integer minutes) {
            // div.session.d15 { height: 1em; margin: 0; padding: 0; }
            return ".duration" + minutes + " { height: " + cssConfiguration.getHeightInEm(minutes) + "em; margin: 0; padding: 0; }";
        }
    });

    public static final F<CssConfiguration, F<P2<P2<String, String>, Integer>, String>> hourToSessionCss = curry(new F2<CssConfiguration, P2<P2<String, String>, Integer>, String>() {
        F<P2<String, String>, String> prepend = P2.tuple(Functions.prepend);

        public String f(CssConfiguration cssConfiguration, P2<P2<String, String>, Integer> p) {
            double em = cssConfiguration.sessionEmStart + (cssConfiguration.getHeightInEm(p._2() * 15));
            return ".start" + prepend.f(p._1()) + " { top: " + oneDigitFormat.format(em) + "em; }";
        }
    });

    // -----------------------------------------------------------------------
    // Session CSS
    // -----------------------------------------------------------------------

    public static F<IncogitoConfiguration, F<Event, List<String>>> generateSessionCss = curry( new F2<IncogitoConfiguration, Event, List<String>>() {
        public List<String> f(final IncogitoConfiguration configuration, final Event event) {
            List<String> formats = list(Session.Format.values()).
                map(Session.Format.toString).
                map(new F<String, String>() {
                    public String f(String format) {
                        return ".format-" + format + " { list-style-image: url('" + configuration.baseurl + "/images/icons/session-format-" + format.toLowerCase() + "-small.png'); }";
                    }
                });

            List<String> levels = configuration.getLevels(event.id).values().
                map(Level.showId.showS_()).
                map(new F<String, String>() {
                    public String f(String level) {
                        return ".level-" + level + " { list-style-image: url('" + configuration.baseurl + "/rest/events/" + event.name + "/icons/levels/" + level + ".png'); }";
                    }
                });

            return formats.append(levels);
        }
    });

    // -----------------------------------------------------------------------
    // Calendar
    // -----------------------------------------------------------------------

    public static final F<Schedule, WebCalendar> webCalendar = new F<Schedule, WebCalendar>() {
        public WebCalendar f(Schedule schedule) {
            Collection<Integer> timeslotHours = schedule.sessions.foldLeft(timeslotFold, Set.<Integer>empty(Ord.intOrd)).toList().reverse().toCollection();
            List<String> rooms = schedule.sessions.foldLeft(roomFolder, Set.<String>empty(Ord.stringOrd)).toList().reverse();

            Map<String, String> attendanceMap = new HashMap<String, String>();

            for (P2<SessionId, UserSessionAssociation> sessionAssociation : schedule.sessionAssociations) {
                attendanceMap.put(sessionAssociation._1().value, sessionAssociation._2().interestLevel.name());
            }

            Collection<Map<String, List<SessionXml>>> dayToRoomToSessionMap = getDayToRoomToSessionMap(schedule, rooms);

            return new WebCalendar(rooms.toCollection(), timeslotHours, attendanceMap, dayToRoomToSessionMap);
        }
    };

    public static Collection<Map<String, List<SessionXml>>> getDayToRoomToSessionMap(Schedule schedule, List<String> rooms) {
        P1<UriBuilder> uriBuilder = P.p(UriBuilder.fromUri("http://poop"));

        F<Session,SessionXml> sessionToXml = XmlFunctions.sessionToXml.f(uriBuilder);

        List<Session> sessions = schedule.sessions.filter(new F<Session, Boolean>() {
            public Boolean f(Session session) {
                return session.timeslot.isSome() && session.room.isSome();
            }
        });

        F2<Set<LocalDate>, Session, Set<LocalDate>> folder = new F2<Set<LocalDate>, Session, Set<LocalDate>>() {
            public Set<LocalDate> f(Set<LocalDate> dateTimeSet, Session session) {
                return dateTimeSet.insert(session.timeslot.some().getStart().toLocalDate());
            }
        };

        Ord<LocalDate> ord = Ord.comparableOrd();
        Set<LocalDate> days = sessions.foldLeft(folder, empty(ord));

        final List<SessionXml> emptyList = List.nil();
        List<Map<String, List<SessionXml>>> list = List.nil();

        for (final LocalDate day : days) {
            F<Session, Boolean> dayFilter = new F<Session, Boolean>() {
                public Boolean f(Session session) {
                    return session.timeslot.some().getStart().toLocalDate().equals(day);
                }
            };

            TreeMap<String, List<SessionXml>> map = rooms.foldLeft(new F2<TreeMap<String, List<SessionXml>>, String, TreeMap<String, List<SessionXml>>>() {
                public TreeMap<String, List<SessionXml>> f(TreeMap<String, List<SessionXml>> stringListTreeMap, String room) {
                    return stringListTreeMap.set(room, emptyList);
                }
            }, TreeMap.<String, List<SessionXml>>empty(Ord.stringOrd));

            for (SessionXml session : sessions.filter(dayFilter).map(sessionToXml)) {
                map = map.set(session.room, map.get(session.room).some().cons(session));
            }

            list = list.cons(map.toMutableMap());
        }

        return list.toCollection();
    }

    private static final F2<Set<Integer>, Session, Set<Integer>> timeslotFold = new F2<Set<Integer>, Session, Set<Integer>>() {
        public Set<Integer> f(Set<Integer> hours, Session session) {
            if (session.timeslot.isNone()) {
                return hours;
            }

            return hours.insert(session.timeslot.some().getStart().getHourOfDay());
        }
    };

    private static final F2<Set<String>, Session, Set<String>> roomFolder = new F2<Set<String>, Session, Set<String>>() {
        public Set<String> f(Set<String> hours, Session session) {
            if (session.room.isNone()) {
                return hours;
            }

            return hours.insert(session.room.some());
        }
    };
}
