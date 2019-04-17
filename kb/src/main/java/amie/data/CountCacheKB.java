/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package amie.data;

import static amie.data.KB.isVariable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import javatools.datatypes.ByteString;

/**
 *
 * @author jlajus
 */
public class CountCacheKB extends KB {

    /**
     * returns the number of instances that fulfill a certain condition
     */
    @Override
    public long countDistinct(ByteString variable, List<ByteString[]> query) {
        if (countCacheEnabled) {
            queryCache qC = new queryCache(query, Arrays.asList(variable));
            Long count = countCache.get(qC);
            if (count != null) {
                countCacheMatch.incrementAndGet();
                return count;
            } else {
                long result = (long) (selectDistinct(variable, query).size());
                countCache.put(qC, result);
                return result;
            }
        } else {
            return (long) (selectDistinct(variable, query).size());
        }
    }

    /**
     * returns the number of distinct pairs (var1,var2) for the query
     */
    public long countDistinctPairs(ByteString var1, ByteString var2,
            List<ByteString[]> query) {

        queryCache qC = null;
        if (countCacheEnabled) {
            qC = new queryCache(query, Arrays.asList(var1, var2));
            Long count = countCache.get(qC);
            if (count != null) {
                countCacheMatch.incrementAndGet();
                return count;
            }
        }

        // Go for the standard plan
        long result = 0;

        try (Instantiator insty1 = new Instantiator(query, var1)) {
            Set<ByteString> bindings = selectDistinct(var1, query);
            for (ByteString val1 : bindings) {
                result += countDistinct(var2, insty1.instantiate(val1));
            }
        }

        if (countCacheEnabled) {
            countCache.put(qC, result);
        }
        return (result);
    }

public long countPairs(ByteString var1, ByteString var2,
			List<ByteString[]> query, int[] queryInfo) {
		
		queryCache qC = null;
		if (countCacheEnabled) {
			qC = new queryCache(query, Arrays.asList(var1, var2));
			Long count = countCache.get(qC);
			if(count != null) {
				countCacheMatch.incrementAndGet();
				return count;
			}
		}

		long result = 0;
		// Approximate count
		ByteString joinVariable = query.get(queryInfo[2])[queryInfo[0]];
		ByteString targetVariable = query.get(queryInfo[3])[queryInfo[1]];
		ByteString targetRelation = query.get(queryInfo[2])[1];

		// Heuristic
		if (relationSize.get(targetRelation) < 50000)
			return countDistinctPairs(var1, var2, query);

		long duplicatesEstimate, duplicatesCard;
		double duplicatesFactor;

		List<ByteString[]> subquery = new ArrayList<ByteString[]>(query);
		subquery.remove(queryInfo[2]); // Remove one of the hard queries
		duplicatesCard = countDistinct(targetVariable, subquery);

		if (queryInfo[0] == 2) {
			duplicatesFactor = (1.0 / functionality(targetRelation)) - 1.0;
		} else {
			duplicatesFactor = (1.0 / inverseFunctionality(targetRelation)) - 1.0;
		}
		duplicatesEstimate = (int) Math.ceil(duplicatesCard * duplicatesFactor);

		try (Instantiator insty1 = new Instantiator(subquery, joinVariable)) {
			for (ByteString value : selectDistinct(joinVariable, subquery)) {
				result += (long) Math
						.ceil(Math.pow(
								countDistinct(targetVariable,
										insty1.instantiate(value)), 2));
			}
		}

		result -= duplicatesEstimate;
		
		if (countCacheEnabled)
			countCache.put(qC, result);
		
		return result;
	}
    
    
    
    // ---------------------------------------------------------------------------
	// Count Cache
	// ---------------------------------------------------------------------------
	
	/**
	 * Class representing a query in the cache.
	 * Equivalent queries should be equals and return the same hash code.
	 */
	public static class queryCache {
		
		public String queryRep;
		private int[][] repr;
		public int ncol;
		public int nline;
		public Set<ByteString> headers, constants;
		public Set<Integer> xorLines, xorCols;
		public long hashLines, hashCols;
		
		
		private static final int COUNTVARIABLEHASH = Integer.MAX_VALUE;
		private static final int VARIABLEHASH = 0;
		
		public static AtomicLong queryCount = new AtomicLong();
		
		public queryCache(List<ByteString[]> query, List<ByteString> countVariables) {
			
			queryRep = stringRepresentation(query, countVariables);
			queryCount.incrementAndGet();
			ncol = query.size() + 2;
			LinkedHashSet<ByteString> args = new LinkedHashSet<ByteString>();
			for (ByteString[] atom : query) {
				args.add(atom[0]);
				args.add(atom[2]);
			}
			nline = args.size();
			headers = new HashSet<ByteString>(query.size());
			constants = new HashSet<ByteString>();
			repr = new int[nline][ncol];
			for (ByteString[] atom : query) {
				headers.add(atom[1]);
			}
			int j = 2;
			int i = 0;
			for (ByteString arg : args) {
				if(isVariable(arg)) {
					repr[i][0] = VARIABLEHASH;
					if(countVariables.contains(arg)) {
						repr[i][1] = COUNTVARIABLEHASH;
					}
				}
				else {
					repr[i][0] = arg.hashCode();
					constants.add(arg);
				}
				j = 2;
				for (ByteString[] atom : query) {
					repr[i][j] = ((atom[0].equals(arg)) ? ((atom[2].equals(arg)) ? 1 : 2) : ((atom[2].equals(arg)) ? 3 : 5) * (atom[1].hashCode()+1));
					j++;
				}
				i++;
			}
		}
		
		@Override
		public int hashCode() {
			if (xorLines == null || xorLines.isEmpty()) {
				hashLines = 0;
				hashCols = 0;
				xorLines = new HashSet<Integer>(nline);
				for (int i = 0; i < nline; i++) {
					int count = 0;
					for (int j = 0; j < ncol; j++) {
						count ^= repr[i][j];
					}
					xorLines.add(count);
					hashLines += count;
				}
				xorCols = new HashSet<Integer>(ncol);
				for (int j = 0; j < ncol; j++) {
					int count = 0;
					for (int i = 0; i < nline; i++) {
						count ^= repr[i][j];
					}
					xorCols.add(count);
					hashCols += count;
				}
			}
			return Objects.hash(nline, ncol, hashLines, hashCols);
		}
		
		public static AtomicLong collisionCount = new AtomicLong();
		
		public String stringRepresentation(List<ByteString[]> _query, List<ByteString> _cV) {
			String r = "";
			for (ByteString[] atom : _query) {
				r += atom[0].toString() + " " + atom[1].toString() + " " + atom[2].toString() + "; ";
			}
			r += _cV.toString();
			return r;
			
		}
		
		@Override
		public boolean equals(Object o) {
			if (!(o instanceof queryCache))
				return false;
			queryCache q2 = (queryCache) o;
			if(hashCode() == q2.hashCode()) {
				if(!_equals(q2)) {
					collisionCount.incrementAndGet();
					//System.err.println(queryRep + "\n" + q2.queryRep + "\n");
					return false;
				}
				return true;
			}
			return false;
		}
		
		
		private boolean _equals(queryCache q2) {
			return (ncol == q2.ncol && 
					nline == q2.nline && 
					headers.equals(q2.headers) &&
					constants.equals(q2.constants) &&
					hashCode() == q2.hashCode() && 
					xorLines.equals(q2.xorLines) && 
					xorCols.equals(q2.xorCols));
		}
	}
	
	private ConcurrentHashMap<queryCache, Long> countCache = new ConcurrentHashMap<>(10000, (float)0.75, 40);
	public static AtomicLong countCacheMatch = new AtomicLong();
	
	public boolean countCacheEnabled = false;
	
}
