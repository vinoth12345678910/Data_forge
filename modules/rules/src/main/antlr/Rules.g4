grammar Rules;

options { caseInsensitive = true; }

deriveRule : colRef '=' expr whenClause? EOF ;
checkRule  : expr whenClause? EOF ;
limitRule  : agg PER colRef '<=' colRef whenClause? EOF ;

whenClause : WHEN expr ;

expr
  : '-' expr                                              # neg
  | expr op=('*' | '/' | '%') expr                        # mul
  | expr op=('+' | '-') expr                              # add
  | expr op=('=' | '<>' | '<' | '<=' | '>' | '>=') expr   # cmp
  | expr IS NOT? NULL                                     # isNull
  | expr NOT? IN '(' expr (',' expr)* ')'                 # in
  | NOT expr                                              # not
  | expr AND expr                                         # and
  | expr OR expr                                          # or
  | CASE (WHEN expr THEN expr)+ ELSE expr END             # case
  | fn=IDENT '(' (expr (',' expr)*)? ')'                  # call
  | agg                                                   # aggExpr
  | colRef                                                # col
  | literal                                               # lit
  | '(' expr ')'                                          # paren
  ;

agg    : fn=(SUM | COUNT | MIN | MAX) '(' ('*' | expr)? (WHERE expr)? (VIA colRef)? ')' ;
colRef : name ('.' name)+ ;
name   : IDENT | QIDENT ;
literal: INT | STRING | TRUE | FALSE | NULL ;

PER : 'per' ; WHEN : 'when' ; THEN : 'then' ; ELSE : 'else' ; END : 'end' ; CASE : 'case' ;
IS : 'is' ; NOT : 'not' ; NULL : 'null' ; IN : 'in' ; AND : 'and' ; OR : 'or' ;
SUM : 'sum' ; COUNT : 'count' ; MIN : 'min' ; MAX : 'max' ; WHERE : 'where' ; VIA : 'via' ;
TRUE : 'true' ; FALSE : 'false' ;
INT    : [0-9]+ ;
STRING : '\'' ( '\'\'' | ~'\'' )* '\'' ;
QIDENT : '"' ~'"'+ '"' ;
IDENT  : [a-z_] [a-z0-9_]* ;
WS     : [ \t\r\n]+ -> skip ;
