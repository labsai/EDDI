import * as React from 'react';
import * as Radium from 'radium';
import { CSSProperties } from 'react';

const styles: CSSProperties = {
  filter: {
    display: 'flex',
  },
  filterArrow: {
    height: '12px',
    marginRight: '41px',
    marginTop: '19px',
  },
  filterTitle: {
    color: '#16325C',
    fontWeight: 'bold',
    textAlign: 'center',
    width: '60px',
    marginTop: '15px',
  },
  searchBox: {
    backgroundColor: '#FFFFFF',
    border: '1px solid #E0E5EE',
    borderRadius: '4px',
    display: 'flex',
    height: '34px',
    marginTop: '7px',
    paddingLeft: '5px',
    width: '200px',
  },
  searchBoxIcon: {
    height: '15px',
    marginLeft: '15px',
    marginTop: '9px',
    width: '15px',
  },
  searchBoxInput: {
    ':focus': {
      outline: 'none',
    },
    backgroundColor: '#FFFFFF',
    border: 'none',
    fontSize: '13px',
    marginLeft: '10px',
    width: '150px',
  },
};

const ArrowRight = require('../../../public/images/ArrowRight.png');
const SearchIcon = require('../../../public/images/searchIcon.png');

interface IProps {
  filter(text: string): void;
}

const FilterComponent = (props: IProps) => (
  <div style={styles.filter}>
    <div style={styles.filterTitle}>{'Filters'}</div>
    <img src={ArrowRight} style={styles.filterArrow} />
    <div style={styles.searchBox}>
      <img src={SearchIcon} style={styles.searchBoxIcon} />
      <input
        type={'text'}
        placeholder={'Find List'}
        style={styles.searchBoxInput}
        onChange={f => props.filter(f.target.value)}
      />
    </div>
  </div>
);

export default Radium(FilterComponent);
