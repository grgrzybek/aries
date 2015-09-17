/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.aries.subsystem.core.archive;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public abstract class AbstractClauseBasedHeader<C extends Clause> implements Header<C> {
	public interface ClauseFactory<C> {
		public C newInstance(String clause);
	}
	
    protected final Set<C> clauses;
    
    public AbstractClauseBasedHeader(Collection<C> clauses) {
        if (clauses.isEmpty()) {
            throw new IllegalArgumentException(String.format(
                    "The header %s must have at least one clause.", getName()));
        }
        this.clauses = Collections.synchronizedSet(new HashSet<C>(clauses));
    }

    public AbstractClauseBasedHeader(String header, ClauseFactory<C> factory) {
    	Collection<String> clauseStrs = new ClauseTokenizer(header).getClauses();
		Set<C> clauses = new HashSet<C>(clauseStrs.size());
		for (String clause : new ClauseTokenizer(header).getClauses()) {
			clauses.add(factory.newInstance(clause));
		}
		this.clauses = Collections.synchronizedSet(clauses);
    }

    @Override
    public final Collection<C> getClauses() {
        return Collections.unmodifiableCollection(clauses);
    }

    @Override
    public int hashCode() {
        int result = 17;
        result = 31 * result + getName().hashCode();
        result = 31 * result + clauses.hashCode();
        return result;
    }
    
    @Override
    public boolean equals(Object o) {
    	if (o == this) {
    		return true;
    	}
    	if (!(o instanceof AbstractClauseBasedHeader)) {
    		return false;
    	}
    	AbstractClauseBasedHeader<?> that = (AbstractClauseBasedHeader<?>)o;
    	return that.getName().equals(this.getName())
    			&& that.clauses.equals(this.clauses);
    }

    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder();
        for (C clause : getClauses()) {
            builder.append(clause).append(',');
        }
        // Remove the trailing comma. Note at least one clause is guaranteed to
        // exist.
        builder.deleteCharAt(builder.length() - 1);
        return builder.toString();
    }
}
