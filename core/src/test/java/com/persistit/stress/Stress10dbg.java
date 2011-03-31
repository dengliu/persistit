/**
 * Copyright (C) 2011 Akiban Technologies Inc.
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License, version 3,
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see http://www.gnu.org/licenses.
 */

package com.persistit.stress;

import com.persistit.ArgParser;
import com.persistit.Debug;
import com.persistit.Exchange;
import com.persistit.Key;
import com.persistit.test.TestResult;

public class Stress10dbg extends StressBase {

    private final static String SHORT_DESCRIPTION = "Random key and value size write/read/delete/traverse loops";

    private final static String LONG_DESCRIPTION = "   Simple stress test that randomly inserts, reads, traverses and\r\n"
            + "   deletes records\r\n";

    private final static String[] ARGS_TEMPLATE = {
            "op|String:wrtd|Operations to perform",
            "repeat|int:1:0:1000000000|Repetitions",
            "count|int:100000:0:1000000000|Number of nodes to populate",
            "size|int:500:1:200000|Size of each data value",
            "seed|int:1:1:20000|Random seed", };

    int _size;
    int _splay;
    int _seed;
    String _opflags;

    Exchange _exs1;
    Exchange _ex1;

    @Override
    public String shortDescription() {
        return SHORT_DESCRIPTION;
    }

    @Override
    public String longDescription() {
        return LONG_DESCRIPTION;
    }

    @Override
    public void setUp() throws Exception {
        super.setUp();
        _ap = new ArgParser("com.persistit.Stress10", _args, ARGS_TEMPLATE);
        _opflags = _ap.getStringValue("op");
        _size = _ap.getIntValue("size");
        _seed = _ap.getIntValue("seed");
        _repeatTotal = _ap.getIntValue("repeat");
        _total = _ap.getIntValue("count");
        _dotGranularity = 100000;

        try {
            // Exchange with Thread-private Tree
            _ex = getPersistit().getExchange("persistit",
                    _rootName + _threadIndex, true);
            _exs = getPersistit().getExchange("persistit", "shared", true);
            _ex1 = getPersistit().getExchange("persistit",
                    _rootName + _threadIndex, false);
            _exs1 = getPersistit().getExchange("persistit", "shared", false);
        } catch (final Exception ex) {
            handleThrowable(ex);
        }
    }

    @Override
    public void executeTest() {

        setPhase("@");
        try {
            _ex.clear().remove(Key.GTEQ);
            _exs.clear().append("stress10").append(Key.BEFORE);
            while (_exs.next()) {
                _exs.append(_threadIndex);
                _exs.remove(Key.GTEQ);
                _exs.cut();
            }
        } catch (final Exception e) {
            handleThrowable(e);
        }
        verboseln();

        for (_repeat = 0; (_repeat < _repeatTotal) && !isStopped(); _repeat++) {
            verboseln();
            verboseln("Starting cycle " + (_repeat + 1) + " of " + _repeatTotal);
            _random.setSeed(_seed);

            for (_count = 0; (_count < _total) && !isStopped(); _count++) {

                dot();
                final int keyInteger = keyInteger(_count);
                int action = random(0, 10);

                // Strong bias toward inserting new data.
                action = action < 5 ? 0 : action < 7 ? 1 : action < 9 ? 2 : 3;

                switch (action) {

                case 0: {
                    // write a record

                    _exs.clear().append("stress10").append(keyInteger)
                            .append(_threadIndex);
                    setupTestValue(_exs, keyInteger, random(20, _size));

                    _ex.clear().append(keyInteger);
                    _ex.getValue().put(_exs.getValue().getEncodedSize() + "@");

                    try {
                        _exs.store();
                        _ex.store();
                    } catch (final Exception e) {
                        handleThrowable(e);

                    }

                    // immediately try reading values back
                    try {
                        _exs1.clear().append("stress10").append(keyInteger)
                                .append(_threadIndex);
                        _ex1.clear().append(keyInteger);
                        _ex1.fetch();
                        int size1 = 0;
                        if (_ex1.getValue().isDefined()
                                && !_ex1.getValue().isNull()) {
                            size1 = parseSize(_ex1);
                        }
                        _exs1.fetch();
                        final int size2 = _exs1.getValue().getEncodedSize();
                        if (size2 != size1) {
                            _result = new TestResult(false, "Value is size "
                                    + size2 + ", should be " + size1 + " key="
                                    + _ex1.getKey());
                            println(_result);
                            Debug.debug1(true);
                            forceStop();
                        }

                    } catch (final Exception e) {
                        handleThrowable(e);

                    }
                    break;
                }
                case 1: {
                    // fetch a record

                    _exs.clear().append("stress10").append(keyInteger)
                            .append(_threadIndex);
                    _ex.clear().append(keyInteger);
                    try {
                        _ex.fetch();
                        int size1 = 0;
                        if (_ex.getValue().isDefined()
                                && !_ex.getValue().isNull()) {
                            size1 = parseSize(_ex);
                        }
                        _exs.fetch();
                        final int size2 = _exs.getValue().getEncodedSize();
                        if (size2 != size1) {
                            _result = new TestResult(false, "Value is size "
                                    + size2 + ", should be " + size1 + " key="
                                    + _ex.getKey());
                            println(_result);
                            Debug.debug1(true);
                            forceStop();
                        }
                    } catch (final Exception e) {
                        handleThrowable(e);
                    }
                    break;
                }
                case 2: {
                    // traverse up to 1000 records

                    _exs.clear().append("stress10").append(keyInteger);
                    for (int count = 0; (count < random(10, 1000))
                            && !isStopped(); count++) {
                        dot();
                        try {
                            if (!_exs.next()) {
                                break;
                            }
                            final int curKeyInteger = _exs.getKey().indexTo(1)
                                    .decodeInt();
                            _exs.append(_threadIndex).fetch().getValue();
                            final int size2 = _exs.getValue().getEncodedSize();
                            _ex.clear().append(curKeyInteger).fetch();
                            int size1 = 0;
                            if (_ex.getValue().isDefined()) {
                                size1 = parseSize(_ex);
                            }
                            if (size2 != size1) {
                                _result = new TestResult(false,
                                        "Value is size " + size2
                                                + ", should be " + size1
                                                + " key=" + _ex.getKey());
                                println(_result);
                                Debug.debug1(true);
                                forceStop();
                            }
                            _exs.cut();
                        } catch (final Exception e) {
                            handleThrowable(e);
                        }
                    }
                    break;
                }
                case 3: {
                    // delete a record

                    _exs.clear().append("stress10").append(keyInteger)
                            .append(_threadIndex);
                    _ex.clear().append(keyInteger);
                    try {
                        _exs.remove();
                        _ex.remove();
                    } catch (final Exception e) {
                        handleThrowable(e);
                    }
                    break;
                }
                }
            }
        }

        verboseln();
        verbose("done");

    }

    int parseSize(final Exchange ex) {
        String s = ex.getValue().getString();
        return Integer.parseInt(s.split("@")[0]);
    }

    int keyInteger(final int counter) {
        final int keyInteger = random(0, _total);
        return keyInteger;
    }

    public static void main(final String[] args) {
        final Stress10dbg test = new Stress10dbg();
        test.runStandalone(args);
    }
}