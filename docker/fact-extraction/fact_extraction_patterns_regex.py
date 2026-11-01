# Python 3 load of CISpaces/factextraction commit 186d23022b33308a27a3c6b97e1a24e941663ab5.
# The only edit is the Python 2 ur prefix, rewritten to r. The patterns are the original ones.

# !/usr/bin/env python
# -*- coding: utf-8 -*-

"""
..
	/////////////////////////////////////////////////////////////////////////
	//
	// (c) Copyright University of Southampton IT Innovation, 2017
	//
	// Copyright in this software belongs to IT Innovation Centre of
	// Gamma House, Enterprise Road, Southampton SO16 7NS, UK.
	//
	// This software may not be used, sold, licensed, transferred, copied
	// or reproduced in whole or in part in any manner or form or in or
	// on any media by any person other than in accordance with the terms
	// of the Licence Agreement supplied with the software, or otherwise
	// without the prior written consent of the copyright owners.
	//
	// This software is distributed WITHOUT ANY WARRANTY, without even the
	// implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR
	// PURPOSE, except where stated in the Licence Agreement supplied with
	// the software.
	//
	// Created By : Stuart E. Middleton
	// Created Date : 2017/09/12
	// Created for Project: IntelAnalysisDSTL
	//
	/////////////////////////////////////////////////////////////////////////
	//
	// Dependancies: None
	//
	/////////////////////////////////////////////////////////////////////////
	'''

Fact extraction parsing functions

"""

# using re.UNICODE | re.DOTALL so we all newlines which will appear in the text. the POS tagging will label them as such if this is important.

# use \A to match from start of tagged sent fragment
# order regex in order of match attempt (match most specific regex first)

# (?:...) = (?:JJ|NN|NNP) = group that does not return a result (use as part of a returning parent group)
# (...){0,2} = must have 0 to 2 matches for this group
# \w* = any number of alphanumeric chars
# \S* = any number of non whitespace chars
# \s\S* = any sequence of chars
# .* = any sequence of chars except newline
# .*? is a non-greedy version of .* matching as few as possible characters (e.g. useful with a sunsequent group match)
# \A = start of string
# \Z = end of string
# x|yy|zzz = allowed alternatives for a group (matched in strict left to right sequence with first match taken i.e. not greedy)
# regex https://docs.python.org/2/library/re.html
# for sent regex matches use [^)]* not \S* so we do not match into other sent subtrees. the () are escaped out (to -LRB- and -RRB-) on serialization prior to regex

import re

#
# regex POS tag replacement (prior to Stanford POS tagging)
#

look_behind_tag = r'\A.*?(?<!__)(?<![A-Za-z0-9])'
look_ahead_tag = r'(?!__)(?![A-Za-z0-9])'

# hashtag = #tag
regex_hashtag = re.compile( look_behind_tag + r'(?P<HASHTAG>\#[a-zA-Z0-9]*)' + look_ahead_tag, re.UNICODE | re.DOTALL | re.IGNORECASE )
# twitter = RT @name:
regex_twitter = re.compile( look_behind_tag + r'(?P<TWITTER>(rt|mt|rt |mt )?\@[a-zA-Z0-9_]*)(\:| \:)?' + look_ahead_tag, re.UNICODE | re.DOTALL | re.IGNORECASE )

#
# regex pattern POS label to Stanford dependency graph mappings
#
dictTagDependancyParseMapping = {
	'NAMESPACE' : '#',
	'URI' : '#',
	'HASHTAG' : '#',
	'TWITTER' : '#',
}

#
# Stanford ReVerb style patterns for extracting bootstrap seed_tuples to capture reliable arg and rel tuples
# seed_tuples are used later to create a sent set with all lexical combinations of arg and rel for input to create a set of open pattern templates for subsequent information extraction
# ReVerb = {arg} {rel} {arg}
# {rel} = V | VP | VW*P
# V = verb + optional particle (base, past, present) + optional adverb
# P = preposition, particle, inf. marker
# W = noun, adjective, adverb, pronoun, determiner
# {arg} = proper noun phrase (more reliable than noun or proper noun patterns)
# note: {prep} and then {rel} are matched first to avoid {arg} where then noun is in fact part of a relation (e.g. made a deal with -> deal is a noun)
# ReVerb takes longest match (so should be greedy) and merges adjacent sequences of argument and relations
#
# ReVerb CITE: Anthony Fader, Stephen Soderland, and Oren Etzioni. 2011. Identifying relations for open information extraction. In Proceedings of the Conference on Empirical Methods in Natural Language Processing (EMNLP '11). Association for Computational Linguistics, Stroudsburg, PA, USA, 1535-1545
#
#listReVerbExecutionOrder = [ 'PREPOSITION', 'RELATION', 'ARGUMENT', 'NUMERIC' ]
listReVerbExecutionOrder = [ 'RELATION', 'ARGUMENT', 'NUMERIC' ]

# classic (includes adverbs which actually can be linked to via dep graph anyway)
# * not used *
dictReVerbPatternsClassic = {

	#'PREPOSITION' : [
	#	re.compile( r'\A.*?(?P<PREPOSITION>\((TO|IN) [^)]*\)( \((TO|IN|DT) [^)]*\)){0,20})', re.UNICODE | re.DOTALL ),
	#	],

	'RELATION' : [
		re.compile( r'\A.*?(?P<RELATION>\((VB|VBD|VBG|VBN|VBP|VBZ) [^)]*\)( \((RP) [^)]*\)){0,1}( \((RB|RBR|RBS) [^)]*\)){0,1}( \((NN|NNS|JJ|JJR|JJS|RB|RBR|RBS|WRB|PRP|PRP$|WP|WP$|DET|WDT|EX) [^)]*\)){0,20}) \(PREPOSITION [^)]*\)', re.UNICODE | re.DOTALL ),
		re.compile( r'\A.*?(?P<RELATION>\((VB|VBD|VBG|VBN|VBP|VBZ) [^)]*\)( \((RP) [^)]*\)){0,1}( \((RB|RBR|RBS) [^)]*\)){0,1}) \(PREPOSITION [^)]*\)', re.UNICODE | re.DOTALL ),
		re.compile( r'\A.*?(?P<RELATION>\((VB|VBD|VBG|VBN|VBP|VBZ) [^)]*\)( \((RP) [^)]*\)){0,1}( \((RB|RBR|RBS) [^)]*\)){0,1})', re.UNICODE | re.DOTALL ),
		],

	'ARGUMENT' : [
		re.compile( r'\A.*?(?P<ARGUMENT>\((JJ|JJR|JJS) [^)]*\)( \((NNP|NNPS|NN|NNS|PRP|PRP$) [^)]*\)){1,20}|\((NNP|NNPS|NN|NNS|PRP|PRP$) [^)]*\)( \((NNP|NNPS|NN|NNS|PRP|PRP$) [^)]*\)){0,20})', re.UNICODE | re.DOTALL ),
		],

	# mumeric value: e.g. approx 10 dead
	'NUMERIC' : [
		re.compile( r'\A.*?(?P<NUMERIC>(\((RB|RBR|RBS) [^)]*\) ){0,20}\(CD [^)]*\)( \((JJ|JJR|JJS) [^)]*\)){1,20})', re.UNICODE | re.DOTALL ),
		],

}

# Revised - ReVerb without the adverbs and adjectives. this means relation terms usually have a nice branhc with a clear head (avoids rels made up of siblings). this in turn means seed matches sub-graph OK.
# the adverbs and adjectives get picked up anyway via dep graph walk as slot nodes.
# {rel} = V | VP | VW*P
# V = verb + optional particle (base, past, present) + optional adverb
# P = preposition, particle, inf. marker
# W = noun, pronoun, determiner
dictReVerbPatterns = {

	#'PREPOSITION' : [
	#	re.compile( r'\A.*?(?P<PREPOSITION>\((TO|IN) [^)]*\)( \((TO|IN|DT) [^)]*\)){0,20})', re.UNICODE | re.DOTALL ),
	#	],

	'RELATION' : [
		re.compile( r'\A.*?(?P<RELATION>\((VB|VBD|VBG|VBN|VBP|VBZ) [^)]*\)( \((RP|JJ) [^)]*\)){0,1}( \((NN|NNS|PRP|PRP$|WP|WP$|DET|WDT|EX) [^)]*\)){0,20}) \(PREPOSITION [^)]*\)', re.UNICODE | re.DOTALL ),
		re.compile( r'\A.*?(?P<RELATION>\((VB|VBD|VBG|VBN|VBP|VBZ) [^)]*\)( \((RP|JJ) [^)]*\)){0,1}) \(PREPOSITION [^)]*\)', re.UNICODE | re.DOTALL ),
		re.compile( r'\A.*?(?P<RELATION>\((VB|VBD|VBG|VBN|VBP|VBZ) [^)]*\)( \((RP|JJ) [^)]*\)){0,1})', re.UNICODE | re.DOTALL ),
		],

	'ARGUMENT' : [
		re.compile( r'\A.*?(?P<ARGUMENT>\((JJ|JJR|JJS) [^)]*\)( \((NNP|NNPS|NN|NNS|PRP|PRP$) [^)]*\)){1,20}|\((NNP|NNPS|NN|NNS|PRP|PRP$) [^)]*\)( \((NNP|NNPS|NN|NNS|PRP|PRP$) [^)]*\)){0,20})', re.UNICODE | re.DOTALL ),
		],

	# mumeric value: e.g. approx 10 dead
	'NUMERIC' : [
		re.compile( r'\A.*?(?P<NUMERIC>(\((RB|RBR|RBS) [^)]*\) ){0,20}\(CD [^)]*\)( \((JJ|JJR|JJS) [^)]*\)){1,20})', re.UNICODE | re.DOTALL ),
		],

}

#
# Seed tuple patterns to used when creating open extraction template patterns
#
setSeedTuples = set( [

	# for OLLIE benchmark work we want a much smaller set with less options for getting it wrong : (low recall, high precision) -> (unfiltered)

	# arg rel
	('ARGUMENT', 'RELATION'),
	('ARGUMENT', 'RELATION', 'RELATION'),
	('ARGUMENT', 'RELATION', 'RELATION', 'RELATION'),

	# arg* rel(1) arg*
	('ARGUMENT', 'RELATION', 'ARGUMENT'),
	('ARGUMENT', 'RELATION', 'ARGUMENT', 'ARGUMENT'),
	('ARGUMENT', 'RELATION', 'ARGUMENT', 'ARGUMENT', 'ARGUMENT'),
	('ARGUMENT', 'RELATION', 'ARGUMENT', 'ARGUMENT', 'ARGUMENT', 'ARGUMENT'),

	('ARGUMENT', 'ARGUMENT', 'RELATION', 'ARGUMENT'),
	('ARGUMENT', 'ARGUMENT', 'RELATION', 'ARGUMENT', 'ARGUMENT'),
	('ARGUMENT', 'ARGUMENT', 'RELATION', 'ARGUMENT', 'ARGUMENT', 'ARGUMENT'),
	('ARGUMENT', 'ARGUMENT', 'RELATION', 'ARGUMENT', 'ARGUMENT', 'ARGUMENT', 'ARGUMENT'),

	('ARGUMENT', 'ARGUMENT', 'ARGUMENT', 'RELATION', 'ARGUMENT'),
	('ARGUMENT', 'ARGUMENT', 'ARGUMENT', 'RELATION', 'ARGUMENT', 'ARGUMENT'),
	('ARGUMENT', 'ARGUMENT', 'ARGUMENT', 'RELATION', 'ARGUMENT', 'ARGUMENT', 'ARGUMENT'),
	('ARGUMENT', 'ARGUMENT', 'ARGUMENT', 'RELATION', 'ARGUMENT', 'ARGUMENT', 'ARGUMENT', 'ARGUMENT'),

	# arg rel(2) arg
	('ARGUMENT', 'RELATION', 'RELATION', 'ARGUMENT'),
	('ARGUMENT', 'RELATION', 'RELATION', 'ARGUMENT', 'ARGUMENT'),
	('ARGUMENT', 'RELATION', 'RELATION', 'ARGUMENT', 'ARGUMENT', 'ARGUMENT'),
	('ARGUMENT', 'RELATION', 'RELATION', 'ARGUMENT', 'ARGUMENT', 'ARGUMENT', 'ARGUMENT'),

	('ARGUMENT', 'ARGUMENT', 'RELATION', 'RELATION', 'ARGUMENT'),
	('ARGUMENT', 'ARGUMENT', 'RELATION', 'RELATION', 'ARGUMENT', 'ARGUMENT'),
	('ARGUMENT', 'ARGUMENT', 'RELATION', 'RELATION', 'ARGUMENT', 'ARGUMENT', 'ARGUMENT'),
	('ARGUMENT', 'ARGUMENT', 'RELATION', 'RELATION', 'ARGUMENT', 'ARGUMENT', 'ARGUMENT', 'ARGUMENT'),

	('ARGUMENT', 'ARGUMENT', 'ARGUMENT', 'RELATION', 'RELATION', 'ARGUMENT'),
	('ARGUMENT', 'ARGUMENT', 'ARGUMENT', 'RELATION', 'RELATION', 'ARGUMENT', 'ARGUMENT'),
	('ARGUMENT', 'ARGUMENT', 'ARGUMENT', 'RELATION', 'RELATION', 'ARGUMENT', 'ARGUMENT', 'ARGUMENT'),
	('ARGUMENT', 'ARGUMENT', 'ARGUMENT', 'RELATION', 'RELATION', 'ARGUMENT', 'ARGUMENT', 'ARGUMENT', 'ARGUMENT'),

	# arg rel(3) arg
	('ARGUMENT', 'RELATION', 'RELATION', 'RELATION', 'ARGUMENT'),
	('ARGUMENT', 'RELATION', 'RELATION', 'RELATION', 'ARGUMENT', 'ARGUMENT'),
	('ARGUMENT', 'RELATION', 'RELATION', 'RELATION', 'ARGUMENT', 'ARGUMENT', 'ARGUMENT'),
	('ARGUMENT', 'RELATION', 'RELATION', 'RELATION', 'ARGUMENT', 'ARGUMENT', 'ARGUMENT', 'ARGUMENT'),

	('ARGUMENT', 'ARGUMENT', 'RELATION', 'RELATION', 'RELATION', 'ARGUMENT'),
	('ARGUMENT', 'ARGUMENT', 'RELATION', 'RELATION', 'RELATION', 'ARGUMENT', 'ARGUMENT'),
	('ARGUMENT', 'ARGUMENT', 'RELATION', 'RELATION', 'RELATION', 'ARGUMENT', 'ARGUMENT', 'ARGUMENT'),
	('ARGUMENT', 'ARGUMENT', 'RELATION', 'RELATION', 'RELATION', 'ARGUMENT', 'ARGUMENT', 'ARGUMENT', 'ARGUMENT'),

	('ARGUMENT', 'ARGUMENT', 'ARGUMENT', 'RELATION', 'RELATION', 'RELATION', 'ARGUMENT'),
	('ARGUMENT', 'ARGUMENT', 'ARGUMENT', 'RELATION', 'RELATION', 'RELATION', 'ARGUMENT', 'ARGUMENT'),
	('ARGUMENT', 'ARGUMENT', 'ARGUMENT', 'RELATION', 'RELATION', 'RELATION', 'ARGUMENT', 'ARGUMENT', 'ARGUMENT'),
	('ARGUMENT', 'ARGUMENT', 'ARGUMENT', 'RELATION', 'RELATION', 'RELATION', 'ARGUMENT', 'ARGUMENT', 'ARGUMENT', 'ARGUMENT'),

	# arg rel arg prep arg
	'''
	('ARGUMENT', 'RELATION', 'ARGUMENT', 'PREPOSITION', 'ARGUMENT'),
	('ARGUMENT', 'RELATION', 'ARGUMENT', 'PREPOSITION', 'ARGUMENT', 'ARGUMENT'),
	('ARGUMENT', 'RELATION', 'ARGUMENT', 'PREPOSITION', 'ARGUMENT', 'ARGUMENT', 'ARGUMENT'),
	('ARGUMENT', 'RELATION', 'ARGUMENT', 'PREPOSITION', 'ARGUMENT', 'ARGUMENT', 'ARGUMENT', 'ARGUMENT'),

	('ARGUMENT', 'RELATION', 'RELATION', 'ARGUMENT', 'PREPOSITION', 'ARGUMENT'),
	('ARGUMENT', 'RELATION', 'RELATION', 'ARGUMENT', 'PREPOSITION', 'ARGUMENT', 'ARGUMENT'),
	('ARGUMENT', 'RELATION', 'RELATION', 'ARGUMENT', 'PREPOSITION', 'ARGUMENT', 'ARGUMENT', 'ARGUMENT'),
	('ARGUMENT', 'RELATION', 'RELATION', 'ARGUMENT', 'PREPOSITION', 'ARGUMENT', 'ARGUMENT', 'ARGUMENT', 'ARGUMENT'),

	('ARGUMENT', 'RELATION', 'RELATION', 'RELATION', 'ARGUMENT', 'PREPOSITION', 'ARGUMENT'),
	('ARGUMENT', 'RELATION', 'RELATION', 'RELATION', 'ARGUMENT', 'PREPOSITION', 'ARGUMENT', 'ARGUMENT'),
	('ARGUMENT', 'RELATION', 'RELATION', 'RELATION', 'ARGUMENT', 'PREPOSITION', 'ARGUMENT', 'ARGUMENT', 'ARGUMENT'),
	('ARGUMENT', 'RELATION', 'RELATION', 'RELATION', 'ARGUMENT', 'PREPOSITION', 'ARGUMENT', 'ARGUMENT', 'ARGUMENT', 'ARGUMENT'),
	'''

	# arg rel arg prep arg rel
	'''
	('ARGUMENT', 'RELATION', 'ARGUMENT', 'PREPOSITION', 'ARGUMENT', 'RELATION'),
	('ARGUMENT', 'RELATION', 'ARGUMENT', 'PREPOSITION', 'ARGUMENT', 'RELATION', 'RELATION'),
	('ARGUMENT', 'RELATION', 'ARGUMENT', 'PREPOSITION', 'ARGUMENT', 'RELATION', 'RELATION', 'RELATION'),
	'''

	# arg rel arg prep arg rel arg
	'''
	('ARGUMENT', 'RELATION', 'ARGUMENT', 'PREPOSITION', 'ARGUMENT', 'RELATION', 'ARGUMENT'),
	('ARGUMENT', 'RELATION', 'ARGUMENT', 'PREPOSITION', 'ARGUMENT', 'RELATION', 'ARGUMENT', 'ARGUMENT'),
	('ARGUMENT', 'RELATION', 'ARGUMENT', 'PREPOSITION', 'ARGUMENT', 'RELATION', 'ARGUMENT', 'ARGUMENT', 'ARGUMENT'),
	('ARGUMENT', 'RELATION', 'ARGUMENT', 'PREPOSITION', 'ARGUMENT', 'RELATION', 'ARGUMENT', 'ARGUMENT', 'ARGUMENT', 'ARGUMENT'),

	('ARGUMENT', 'RELATION', 'ARGUMENT', 'PREPOSITION', 'ARGUMENT', 'RELATION', 'RELATION', 'ARGUMENT'),
	('ARGUMENT', 'RELATION', 'ARGUMENT', 'PREPOSITION', 'ARGUMENT', 'RELATION', 'RELATION', 'ARGUMENT', 'ARGUMENT'),
	('ARGUMENT', 'RELATION', 'ARGUMENT', 'PREPOSITION', 'ARGUMENT', 'RELATION', 'RELATION', 'ARGUMENT', 'ARGUMENT', 'ARGUMENT'),
	('ARGUMENT', 'RELATION', 'ARGUMENT', 'PREPOSITION', 'ARGUMENT', 'RELATION', 'RELATION', 'ARGUMENT', 'ARGUMENT', 'ARGUMENT', 'ARGUMENT'),

	('ARGUMENT', 'RELATION', 'ARGUMENT', 'PREPOSITION', 'ARGUMENT', 'RELATION', 'RELATION', 'RELATION', 'ARGUMENT'),
	('ARGUMENT', 'RELATION', 'ARGUMENT', 'PREPOSITION', 'ARGUMENT', 'RELATION', 'RELATION', 'RELATION', 'ARGUMENT', 'ARGUMENT'),
	('ARGUMENT', 'RELATION', 'ARGUMENT', 'PREPOSITION', 'ARGUMENT', 'RELATION', 'RELATION', 'RELATION', 'ARGUMENT', 'ARGUMENT', 'ARGUMENT'),
	('ARGUMENT', 'RELATION', 'ARGUMENT', 'PREPOSITION', 'ARGUMENT', 'RELATION', 'RELATION', 'RELATION', 'ARGUMENT', 'ARGUMENT', 'ARGUMENT', 'ARGUMENT'),
	'''

	# arg prep rel arg
	'''
	('ARGUMENT', 'PREPOSITION', 'RELATION', 'ARGUMENT'),
	('ARGUMENT', 'PREPOSITION', 'RELATION', 'ARGUMENT', 'ARGUMENT'),
	('ARGUMENT', 'PREPOSITION', 'RELATION', 'ARGUMENT', 'ARGUMENT', 'ARGUMENT'),
	('ARGUMENT', 'PREPOSITION', 'RELATION', 'ARGUMENT', 'ARGUMENT', 'ARGUMENT', 'ARGUMENT'),

	('ARGUMENT', 'PREPOSITION', 'RELATION', 'RELATION', 'ARGUMENT'),
	('ARGUMENT', 'PREPOSITION', 'RELATION', 'RELATION', 'ARGUMENT', 'ARGUMENT'),
	('ARGUMENT', 'PREPOSITION', 'RELATION', 'RELATION', 'ARGUMENT', 'ARGUMENT', 'ARGUMENT'),
	('ARGUMENT', 'PREPOSITION', 'RELATION', 'RELATION', 'ARGUMENT', 'ARGUMENT', 'ARGUMENT', 'ARGUMENT'),

	('ARGUMENT', 'PREPOSITION', 'RELATION', 'RELATION', 'RELATION', 'ARGUMENT'),
	('ARGUMENT', 'PREPOSITION', 'RELATION', 'RELATION', 'RELATION', 'ARGUMENT', 'ARGUMENT'),
	('ARGUMENT', 'PREPOSITION', 'RELATION', 'RELATION', 'RELATION', 'ARGUMENT', 'ARGUMENT', 'ARGUMENT'),
	('ARGUMENT', 'PREPOSITION', 'RELATION', 'RELATION', 'RELATION', 'ARGUMENT', 'ARGUMENT', 'ARGUMENT', 'ARGUMENT'),
	'''

	# arg rel prep arg
	'''
	('ARGUMENT', 'RELATION', 'PREPOSITION', 'ARGUMENT'),
	('ARGUMENT', 'RELATION', 'PREPOSITION', 'ARGUMENT', 'ARGUMENT'),
	('ARGUMENT', 'RELATION', 'PREPOSITION', 'ARGUMENT', 'ARGUMENT', 'ARGUMENT'),
	('ARGUMENT', 'RELATION', 'PREPOSITION', 'ARGUMENT', 'ARGUMENT', 'ARGUMENT', 'ARGUMENT'),
	('ARGUMENT', 'RELATION', 'RELATION', 'PREPOSITION', 'ARGUMENT'),
	('ARGUMENT', 'RELATION', 'RELATION', 'PREPOSITION', 'ARGUMENT', 'ARGUMENT'),
	('ARGUMENT', 'RELATION', 'RELATION', 'PREPOSITION', 'ARGUMENT', 'ARGUMENT', 'ARGUMENT'),
	('ARGUMENT', 'RELATION', 'RELATION', 'PREPOSITION', 'ARGUMENT', 'ARGUMENT', 'ARGUMENT', 'ARGUMENT'),
	('ARGUMENT', 'RELATION', 'RELATION', 'RELATION', 'PREPOSITION', 'ARGUMENT'),
	('ARGUMENT', 'RELATION', 'RELATION', 'RELATION', 'PREPOSITION', 'ARGUMENT', 'ARGUMENT'),
	('ARGUMENT', 'RELATION', 'RELATION', 'RELATION', 'PREPOSITION', 'ARGUMENT', 'ARGUMENT', 'ARGUMENT'),
	('ARGUMENT', 'RELATION', 'RELATION', 'RELATION', 'PREPOSITION', 'ARGUMENT', 'ARGUMENT', 'ARGUMENT', 'ARGUMENT'),
	'''

	# arg prep rel arg prep arg
	# ?? arg prep rel arg prep arg rel
	# ?? arg prep rel arg prep arg rel arg

	# arg prep arg
	'''
	('ARGUMENT', 'PREPOSITION', 'ARGUMENT'),
	('ARGUMENT', 'PREPOSITION', 'ARGUMENT', 'ARGUMENT'),
	('ARGUMENT', 'PREPOSITION', 'ARGUMENT', 'ARGUMENT', 'ARGUMENT'),
	('ARGUMENT', 'PREPOSITION', 'ARGUMENT', 'ARGUMENT', 'ARGUMENT', 'ARGUMENT'),
	'''

	# arg prep arg prep arg
	'''
	('ARGUMENT', 'PREPOSITION', 'ARGUMENT', 'PREPOSITION', 'ARGUMENT'),
	('ARGUMENT', 'PREPOSITION', 'ARGUMENT', 'PREPOSITION', 'ARGUMENT', 'ARGUMENT'),
	('ARGUMENT', 'PREPOSITION', 'ARGUMENT', 'PREPOSITION', 'ARGUMENT', 'ARGUMENT', 'ARGUMENT'),
	('ARGUMENT', 'PREPOSITION', 'ARGUMENT', 'PREPOSITION', 'ARGUMENT', 'ARGUMENT', 'ARGUMENT', 'ARGUMENT'),
	'''

	# arg prep arg rel arg
	# arg prep arg rel arg prep arg
	# arg prep arg rel arg prep arg rel
	# arg prep arg rel arg prep arg rel arg

	# arg rel prep
	# arg rel prep arg prep arg

	# arg rel prep arg rel arg
	# arg rel prep arg rel arg prep arg
	# arg rel prep arg rel arg prep arg rel
	# arg rel prep arg rel arg prep arg rel arg

	# HASHTAG rel arg

])

dictSeedToTemplateMapping = {
	#'PREPOSITION' : 'prep',
	'RELATION' : 'rel',
	'ARGUMENT' : 'arg',
	'NUMERIC' : 'num',
	}


#
# Dependency graph navigation restrictions
# Depending on the extracted variable type a different branch navigation path will be chosen to capture relevant dependent text under each branches root node. This captures the contextual text for each extracted variable.
#

# set of known graph dependency types for variable pretty print
#setGraphDepTypes = set([ 'prep','rel','arg','num' ])
setGraphDepTypes = set([ 'rel','arg','num' ])

# graph dependency types to allow for collapsing different branch types
# when generating matched variable collapsed branched for pretty print later
dictCollapseDepTypes = {
	#'prep' : set([
	#	'case', 'case:of', 'case:by', 'det'
	#	]),
	'rel' : set([
		'advmod', 'aux', 'auxpass',
		'cop', 'prt', 'det', 'neg'
		# is below ok ?
		'nsubj', 'nsubjpass', 'dobj', 'xcomp', 'ccomp', 'iobj',
		'case', 'case:of', 'case:by',
		# consider adding compound if prt not good enough

		# added from grav
		'compound', 'amod', 'nummod', 'appos'
		]),
	'arg' : set([
		'amod', 'compound', 'det', 'neg', 'nummod', 'advmod', 'appos',
		'case', 'case:of', 'case:by',
		'relcl', 'nfincl', 'cc', 'conj',
		# 'dep' removed as it allows # (which includes RT @screenname)
		]),
	'''
	'arg_noun' : set([
		'amod', 'compound', 'det', 'neg', 'nummod', 'advmod', 'appos',
		'case', 'case:of', 'case:by',
		# allow unknowns as important context like numbers are often labelled as a generic dep
		#'dep'
		]),
	'arg_other' : set([
		'amod', 'compound', 'det', 'neg', 'nummod', 'advmod', 'appos',
		'case', 'case:of', 'case:by',
		'relcl', 'nfincl', 'cc', 'conj'
		# allow unknowns as important context like numbers are often labelled as a generic dep
		#'dep'
		]),
	'''
	'num' : set([
		'advmod', 'nmod', 'acl',
		'case',
		#'dep'
		]),
}

# adverbs and adjectives to include as context (1 deep only in tree) since they can change the meaning of the primary variable (e.g. verb)
# when generating open information extraction patterns
listContextualDepTypes = [
	# adverbial clause modifier (verb modifier), adverbial modifier (adverb modifier), adjectival modifier (adjective modifier)
	'advcl', 'advmod', 'amod',
	# negation (negative context for noun), auxillary (context to verb), coordinating conjunction (context to preceeding conj)
	'aux', 'auxpass', 'neg', 'cc',
	# case (preposition associated with noun)
	'case', 'case:of', 'case:by'
	]

