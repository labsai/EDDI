import * as React from 'react';
import * as Radium from 'radium';
import { CSSProperties } from 'react';
import { pageEnum } from '../pages/pageEnum';

const styles: CSSProperties = {
  filter: {
    display: 'flex',
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

const SearchIcon = require('../../../public/images/searchIcon.png');

interface IProps {
  page: pageEnum;
  filter(text: string): void;
}

function getSearchName(page: pageEnum) {
  if (page === pageEnum.httpCalls) {
    return 'HTTP calls';
  } else if (page === pageEnum.gitCalls) {
    return 'Git calls';
  } else {
    return pageEnum[page];
  }
}

const FilterComponent = (props: IProps) => (
  <div style={styles.filter}>
    <div style={styles.searchBox}>
      <img src={SearchIcon} style={styles.searchBoxIcon} />
      <input
        type={'text'}
        placeholder={`Find ${getSearchName(props.page)}`}
        style={styles.searchBoxInput}
        onChange={f => props.filter(f.target.value)}
      />
    </div>
  </div>
);

export default Radium(FilterComponent);
