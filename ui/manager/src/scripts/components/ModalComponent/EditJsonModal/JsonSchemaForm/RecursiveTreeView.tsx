import * as React from 'react';
import { makeStyles } from '@material-ui/core/styles';
import {
  SMALL_FONT,
  SMALL_FONT2,
  WHITE_COLOR,
  YELLOW_COLOR,
} from '../../../../../styles/DefaultStylingProperties';

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

    /* Hide the nested list */
    '& .nested': {
      display: 'none',
    },

    '& .leaf': {
      color: YELLOW_COLOR,
    },

    '& .dots': {
      display: 'block',
      color: YELLOW_COLOR,
    },
    '& .hidden': {
      display: 'none',
    },
    /* Show the nested list when the user clicks on the caret/arrow (with JavaScript) */
    '& .active': {
      display: 'block',
    },
  },
  toggle: {
    display: 'flex',
    flexDirection: 'row',
    alignItems: 'center',
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
      return (
        <li key={key + reactKey}>
          {buildNode(key, object.id)}{' '}
          <strong>{`${
            isArray(object[key]) ? '[' : isObject(object[key]) ? '{' : ''
          }`}</strong>
          <ul className="nested">
            {isPrimative(object[key])
              ? buildLeaf(object[key])
              : isArray(object[key])
              ? loopArray(object[key])
              : processObject(object[key])}
          </ul>
          <strong>{`${
            isArray(object[key]) ? ']' : isObject(object[key]) ? '}' : ''
          }`}</strong>
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
