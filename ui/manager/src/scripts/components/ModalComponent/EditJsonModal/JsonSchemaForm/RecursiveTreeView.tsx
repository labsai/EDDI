import * as React from 'react';
import { makeStyles } from '@material-ui/core/styles';
import {
  SMALL_FONT,
  SMALL_FONT2,
  WHITE_COLOR,
  YELLOW_COLOR,
} from '../../../../../styles/DefaultStylingProperties';
import * as _ from 'lodash';

const useStyles = makeStyles({
  treeContainer: {
    '& *': {
      color: WHITE_COLOR,
      fontFamily: 'Monaco',
      fontSize: SMALL_FONT,
    },
    '& ul': {
      listStyleType: 'none',
      paddingLeft: '10px',
    },

    '& .node': {
      cursor: 'pointer',
      userSelect: 'none' /* Prevent text selection */,
    },

    '& .toggler': {
      cursor: 'pointer',
      display: 'inline-block',
      userSelect: 'none' /* Prevent text selection */,

      /* Create the caret/arrow with a unicode, and style it */
      '&:before': {
        content: '"+"',
        fontSize: '17px',
        color: '#ae81fe',
        marginRight: '6px',
        border: `1px solid #ae81fe`,
        lineHeight: '12px',
        display: 'flex',
        justifyContent: 'center',
        alignItems: 'center',
        height: '13px',
        width: '14px',
        paddingBottom: '2px',
        paddingLeft: '1px',
        borderRadius: '2px',
      },
    },

    /* Rotate the caret/arrow icon when clicked on (using JavaScript) */
    '& .node-down': {
      '&:before': {
        content: '"-"!important',
        border: `1px solid #65d9ef!important`,
        color: '#65d9ef!important',
        paddingBottom: '2px!important',
      },
    },

    '& .dots': {
      display: 'block',
      color: YELLOW_COLOR,
      fontWeight: 'bold',
    },

    /* Hide the nested list */
    '& .nested': {
      display: 'inline-flex',
      paddingLeft: '0',

      '& > div': {
        display: 'none',
      },
      '& > li': {
        display: 'none',
      },
      '& > span': {
        display: 'block',
      },
    },

    /* Show the nested list when the user clicks on the caret/arrow (with JavaScript) */
    '& .active': {
      display: 'block',
      paddingLeft: '10px!important',

      '& > div': {
        display: 'block!important',
      },
      '& > li': {
        display: 'block!important',
      },
      '& > .dots': {
        display: 'none!important',
      },
      '& > .brackets': {
        marginLeft: '-10px',
      },
    },

    '& .leaf': {
      color: YELLOW_COLOR,
    },
  },
  toggle: {
    display: 'flex',
    flexDirection: 'row',
    alignItems: 'center',
  },
  empty: {
    opacity: 0.6,
  },
});

export const rjvStyles = {
  rjv: {
    borderRadius: '5px',
    fontSize: SMALL_FONT2,
    marginLeft: '10px',
  },
};

const RecursiveTreeView = ({ data }) => {
  const classes = useStyles();

  const processObject = (object) => {
    return Object.keys(object).map((key, reactKey) => {
      if (key === 'id') {
        return;
      }
      if (isPrimative(object[key])) {
        return (
          <li key={key + reactKey}>
            {buildNodeNoToggle(key, `"${object[key]}"`, object.id)}
          </li>
        );
      }
      if (isArray(object[key]) && _.isEmpty(object[key])) {
        return (
          <li key={key + reactKey}>
            {buildNodeNoToggleObject(key, '[]', object.id)}
          </li>
        );
      }
      if (isObject(object[key]) && !Object.keys(object[key]).length) {
        return (
          <li key={key + reactKey}>
            {buildNodeNoToggleObject(key, '{}', object.id)}
          </li>
        );
      }
      return (
        <li key={key + reactKey}>
          {buildNode(key, object.id)}{' '}
          <ul className="nested">
            <strong className="brackets">{`${
              isArray(object[key]) ? '[' : isObject(object[key]) ? '{' : ''
            }`}</strong>
            {isPrimative(object[key])
              ? buildLeaf(object[key])
              : isArray(object[key])
              ? loopArray(object[key])
              : processObject(object[key])}
            <strong className="dots">{`${
              isArray(object[key]) && !_.isEmpty(object[key])
                ? '...'
                : isObject(object[key]) && Object.keys(object[key]).length
                ? '...'
                : ''
            }`}</strong>
            <strong className="brackets">{`${
              isArray(object[key]) ? ']' : isObject(object[key]) ? '}' : ''
            }`}</strong>
          </ul>
        </li>
      );
    });
  };

  const loopArray = (array) =>
    array.map((value, key) => (
      <div key={key}>
        {isPrimative(value)
          ? buildLeaf(value)
          : isArray(value)
          ? loopArray(value)
          : processObject(value)}
      </div>
    ));

  const isArray = (value) => Array.isArray(value);
  const isObject = (value) => typeof value === 'object';

  const isPrimative = (value) => {
    return (
      typeof value === 'string' ||
      typeof value === 'number' ||
      typeof value === 'boolean'
    );
  };

  const handleScrollToElement = (id?: string) => {
    if (!id) {
      return;
    }
    const element = document.getElementById(id);
    element.scrollIntoView({ behavior: 'smooth' });
  };

  const buildNode = (key: string, id?: string) => (
    <>
      <span
        className="toggler"
        onClick={(e) => {
          toggle(e);
        }}></span>
      <span
        className="node"
        onClick={(e) => {
          handleScrollToElement(id);
        }}>
        {key}:
      </span>
    </>
  );

  const buildNodeNoToggle = (key: string, value: string, id?: string) => (
    <span
      className="node"
      onClick={() => {
        handleScrollToElement(id);
      }}>
      {key}: <span className="leaf">{value}</span>
    </span>
  );

  const buildNodeNoToggleObject = (key: string, value: string, id?: string) => (
    <span
      className="node"
      onClick={() => {
        handleScrollToElement(id);
      }}>
      {key}: <span className={classes.empty}>{value}</span>
    </span>
  );

  const buildLeaf = (value: string) => <li className="leaf">"{value}"</li>;

  const toggle = (event) => {
    event.target.parentElement
      .querySelector('.nested')
      .classList.toggle('active');
    event.target.classList.toggle('node-down');
  };

  return (
    <div className={classes.treeContainer}>
      <ul>{processObject(data)}</ul>
    </div>
  );
};

export default RecursiveTreeView;
