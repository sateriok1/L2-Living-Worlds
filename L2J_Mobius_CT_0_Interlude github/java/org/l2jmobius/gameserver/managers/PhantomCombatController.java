/*
 * Copyright (c) 2013 L2jMobius
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR
 * IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package org.l2jmobius.gameserver.managers;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Shared, actor-independent execution lifecycle. The game adapter supplies native legality and actions;
 * movement disability never implies casting disability. One State belongs to one phantom's playstyle state.
 */
public final class PhantomCombatController
{
	public enum Outcome
	{
		IDLE, BUSY, APPROACHING, BLOCKED, STARTED, REJECTED, ATTACKING, CAST_ENDED, TARGET_LOST, TIMED_OUT
	}

	public record Request(int skillId, int targetId, boolean offensive, int repeatMs, long durationMs, boolean urgent)
	{
	}

	/** Native actions are deliberately separate from policy, so sequence tests use the same executor as the server. */
	public interface Actor
	{
		boolean available();
		boolean casting();
		default boolean ownsCast(int skillId) { return true; }
		boolean pendingTargetAlive(int targetId);
		boolean inReach();
		boolean canMove();
		void approach();
		boolean cast();
		void abortCast();
		void attack();
	}

	/** The native cast handoff, including the movement stop normally supplied by PlayerAI. */
	public interface CastPort
	{
		boolean legal();
		void stopMoving();
		void launch();
		boolean accepted();
	}

	public static boolean launchCast(CastPort actor, int hitTime, boolean simultaneous)
	{
		if (!actor.legal())
		{
			return false;
		}
		if ((hitTime > 50) && !simultaneous)
		{
			actor.stopMoving();
		}
		actor.launch();
		return actor.accepted();
	}

	public static final class State
	{
		private final Map<Long, Long> _retryAt = new LinkedHashMap<>();
		private final Map<Integer, Long> _repeatAt = new LinkedHashMap<>();
		private Request _pending;
		private long _deadline;
		private long _nextActionAt;
		private Outcome _last = Outcome.IDLE;
		private long _started;
		private long _rejected;
		private long _ended;
		private long _timeouts;

		public synchronized boolean ready(int skillId, int targetId, long now)
		{
			return (now >= _retryAt.getOrDefault(key(skillId, targetId), 0L)) && (now >= _repeatAt.getOrDefault(skillId, 0L));
		}

		public synchronized boolean reactionReady(long now)
		{
			return now >= _nextActionAt;
		}

		public synchronized void resetReaction()
		{
			_nextActionAt = 0;
		}

		public synchronized String summary()
		{
			return _last + " started=" + _started + " rejected=" + _rejected + " ended=" + _ended + " timeouts=" + _timeouts;
		}

		public synchronized Outcome lastOutcome()
		{
			return _last;
		}
	}

	private PhantomCombatController()
	{
	}

	private static long key(int skillId, int targetId)
	{
		return ((long) skillId << 32) | (targetId & 0xffffffffL);
	}

	private static <K> void bound(Map<K, Long> map)
	{
		while (map.size() > 64)
		{
			map.remove(map.keySet().iterator().next());
		}
	}

	/** Observe a cast without claiming that its damage, heal or effect landed. A cleared native flag only means it ended. */
	public static Outcome observe(State state, Actor actor, long now)
	{
		synchronized (state)
		{
			if (state._pending != null)
			{
				if (!actor.casting() || !actor.ownsCast(state._pending.skillId()))
				{
					state._pending = null;
					state._ended++;
					state._last = Outcome.CAST_ENDED;
				}
				else if (state._pending.offensive() && !actor.pendingTargetAlive(state._pending.targetId()))
				{
					actor.abortCast();
					state._pending = null;
					state._last = Outcome.TARGET_LOST;
				}
				else if (now >= state._deadline)
				{
					actor.abortCast();
					state._pending = null;
					state._timeouts++;
					state._last = Outcome.TIMED_OUT;
				}
			}
			return actor.casting() ? Outcome.BUSY : state._last;
		}
	}

	public static Outcome execute(State state, Actor actor, Request request, boolean physical, long now)
	{
		synchronized (state)
		{
			observe(state, actor, now);
			if (!actor.available())
			{
				return state._last = Outcome.BLOCKED;
			}
			if (actor.casting())
			{
				return state._last = Outcome.BUSY;
			}
			if ((request != null) && (request.urgent() || state.reactionReady(now)) && state.ready(request.skillId(), request.targetId(), now))
			{
				if (!actor.inReach())
				{
					if (actor.canMove())
					{
						actor.approach();
						return state._last = Outcome.APPROACHING;
					}
					return state._last = Outcome.BLOCKED;
				}
				if (actor.cast())
				{
					state._started++;
					state._nextActionAt = now + 250;
					state._repeatAt.put(request.skillId(), now + Math.max(0, request.repeatMs()));
					bound(state._repeatAt);
					state._retryAt.remove(key(request.skillId(), request.targetId()));
					if (actor.casting())
					{
						state._pending = request;
						state._deadline = now + Math.max(3000, request.durationMs() + 2000);
					}
					return state._last = Outcome.STARTED;
				}
				state._rejected++;
				state._retryAt.put(key(request.skillId(), request.targetId()), now + 2000);
				bound(state._retryAt);
				state._last = Outcome.REJECTED;
				if (physical)
				{
					actor.attack();
				}
				return state._last;
			}
			if (physical)
			{
				actor.attack();
				return state._last = Outcome.ATTACKING;
			}
			return state._last = Outcome.IDLE;
		}
	}
}
