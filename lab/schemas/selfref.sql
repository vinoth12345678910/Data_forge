-- Lab schema: self reference to a non-primary-key unique column, and a composite key.
create table emp (id bigint primary key, code text unique not null, boss_code text references emp(code));
create table dept (org int not null, id int not null, name text not null, primary key (org, id));
create table staff (id bigint primary key, org int not null, dept_id int not null, foreign key (org, dept_id) references dept(org, id));
