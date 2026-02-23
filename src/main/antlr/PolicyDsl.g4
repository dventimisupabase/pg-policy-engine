grammar PolicyDsl;

// --- Top Level ---
policySet : policy* EOF ;

policy
    : POLICY identifier policyType commandList selectorClause clauseBlock
    ;

policyType : PERMISSIVE | RESTRICTIVE ;

commandList : FOR command (COMMA command)* ;

command : SELECT | INSERT | UPDATE | DELETE ;

selectorClause : SELECTOR selector ;

// --- Selectors (left-recursive for AND/OR) ---
selector
    : selector AND selector          # selectorAnd
    | selector OR selector           # selectorOr
    | LPAREN selector RPAREN         # selectorParen
    | ALL                            # selectorAll
    | HAS_COLUMN LPAREN identifier (COMMA columnType)? RPAREN  # selectorHasColumn
    | IN_SCHEMA LPAREN identifier RPAREN                       # selectorInSchema
    | NAMED LPAREN stringLiteral RPAREN                        # selectorNamed
    | TAGGED LPAREN stringLiteral RPAREN                       # selectorTagged
    ;

columnType : TEXT | INTEGER_TYPE | BIGINT | UUID | BOOLEAN_TYPE | TIMESTAMP | JSONB ;

// --- Clauses ---
clauseBlock : CLAUSE clause (OR CLAUSE clause)* ;

clause : atom (AND atom)* ;

// --- Atoms ---
atom
    : valueSource binaryOp valueSource   # binaryAtom
    | valueSource unaryOp                # unaryAtom
    | traversalAtom                      # traversalAtomRule
    ;

traversalAtom
    : EXISTS LPAREN relationship COMMA LBRACE clause RBRACE RPAREN
    ;

relationship
    : REL LPAREN relSource COMMA identifier COMMA identifier COMMA identifier RPAREN
    ;

relSource : identifier | UNDERSCORE ;

// --- Value Sources ---
valueSource
    : COL LPAREN identifier RPAREN               # colSource
    | SESSION LPAREN stringLiteral RPAREN         # sessionSource
    | LIT LPAREN literalValue RPAREN              # litSource
    | FN LPAREN identifier COMMA LBRACKET argList? RBRACKET RPAREN  # fnSource
    ;

argList : valueSource (COMMA valueSource)* ;

// --- Operators ---
binaryOp
    : EQ | NEQ | LT | GT | LTE | GTE
    | IN | NOT_IN | LIKE | NOT_LIKE
    ;

unaryOp : IS_NULL | IS_NOT_NULL ;

// --- Literals ---
literalValue
    : stringLiteral       # stringLiteralValue
    | integerLiteral      # integerLiteralValue
    | booleanLiteral      # booleanLiteralValue
    | NULL                # nullLiteralValue
    | listLiteral         # listLiteralValue
    ;

listLiteral : LBRACKET literalValue (COMMA literalValue)* RBRACKET ;

stringLiteral : STRING_LITERAL ;

integerLiteral : MINUS? INTEGER_LITERAL ;

booleanLiteral : TRUE | FALSE ;

identifier : IDENTIFIER | POLICY | PERMISSIVE | RESTRICTIVE | FOR | SELECTOR
           | CLAUSE | ALL | TEXT | BIGINT | UUID | TIMESTAMP | JSONB ;

// --- Keywords ---
POLICY : 'POLICY' ;
PERMISSIVE : 'PERMISSIVE' ;
RESTRICTIVE : 'RESTRICTIVE' ;
FOR : 'FOR' ;
SELECT : 'SELECT' ;
INSERT : 'INSERT' ;
UPDATE : 'UPDATE' ;
DELETE : 'DELETE' ;
SELECTOR : 'SELECTOR' ;
CLAUSE : 'CLAUSE' ;
AND : 'AND' ;
OR : 'OR' ;
NOT : 'NOT' ;
ALL : 'ALL' ;

HAS_COLUMN : 'has_column' ;
IN_SCHEMA : 'in_schema' ;
NAMED : 'named' ;
TAGGED : 'tagged' ;

COL : 'col' ;
SESSION : 'session' ;
LIT : 'lit' ;
FN : 'fn' ;

EXISTS : 'exists' ;
REL : 'rel' ;

TRUE : 'true' ;
FALSE : 'false' ;
NULL : 'null' ;

IS_NULL : 'IS' WS_INLINE+ 'NULL' ;
IS_NOT_NULL : 'IS' WS_INLINE+ 'NOT' WS_INLINE+ 'NULL' ;

NOT_IN : 'NOT' WS_INLINE+ 'IN' ;
NOT_LIKE : 'NOT' WS_INLINE+ 'LIKE' ;

IN : 'IN' ;
LIKE : 'LIKE' ;

// --- Column types ---
TEXT : 'text' ;
INTEGER_TYPE : 'integer' ;
BIGINT : 'bigint' ;
UUID : 'uuid' ;
BOOLEAN_TYPE : 'boolean' ;
TIMESTAMP : 'timestamp' ;
JSONB : 'jsonb' ;

// --- Operators ---
EQ : '=' ;
NEQ : '!=' ;
LT : '<' ;
GT : '>' ;
LTE : '<=' ;
GTE : '>=' ;

// --- Punctuation ---
LPAREN : '(' ;
RPAREN : ')' ;
LBRACKET : '[' ;
RBRACKET : ']' ;
LBRACE : '{' ;
RBRACE : '}' ;
COMMA : ',' ;
UNDERSCORE : '_' ;
MINUS : '-' ;

// --- Literals ---
STRING_LITERAL : '\'' (~['\r\n\\] | '\\' .)* '\'' ;
INTEGER_LITERAL : [0-9]+ ;
IDENTIFIER : [a-zA-Z] [a-zA-Z0-9_]* ;

// --- Whitespace & Comments ---
fragment WS_INLINE : [ \t] ;
WS : [ \t\r\n]+ -> skip ;
LINE_COMMENT : '//' ~[\r\n]* -> skip ;
BLOCK_COMMENT : '/*' .*? '*/' -> skip ;
